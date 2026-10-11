#!/usr/bin/env python3
"""Train the SMS decision head on Gemma embeddings of licensed public data.

Use --prepare, export rows.base64 with GemmaEmbeddingExport.java and the pinned
LiteRT-LM 0.18.0 JVM runtime, then run --embeddings. Android and the exporter
share GemmaTextInput. No private SMS belongs in this training pipeline.
"""
import argparse
import base64
import csv
import hashlib
import io
import json
import re
import struct
import urllib.request
import zipfile
from pathlib import Path

import numpy as np
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import confusion_matrix, precision_score, recall_score
from sklearn.model_selection import StratifiedGroupKFold
from sklearn.neighbors import NearestNeighbors

REPOSITORY = Path(__file__).resolve().parents[1]
MODEL_SHA = '2d079ee2f6f066b1f368e8d7c819f55214eaef1d0513b312321901f30ab286fb'
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--cache-dir', type=Path, required=True)
parser.add_argument('--prepare', action='store_true')
parser.add_argument('--embeddings', type=Path)
parser.add_argument('--output', type=Path, default=REPOSITORY / 'android/app/src/main/assets/spam')
parser.add_argument('--parity-output', type=Path, default=REPOSITORY / 'android/app/src/test/resources/gemma-head-parity.tsv')
args = parser.parse_args()
root = args.cache_dir
root.mkdir(parents=True, exist_ok=True)
sources = json.loads((REPOSITORY / 'tools/spam-sources.json').read_text())


def normalized(text):
    text = re.sub(r'https?://\S+|www\.\S+', ' url ', text[:3000].lower())
    text = re.sub(r'\d+', ' num ', text)
    return ' '.join(re.findall(r'[a-z]+', text))


if args.prepare:
    raw = []
    for name, source, member in zip(['id', 'en'], sources, ['dataset_sms_spam_v1.csv', 'SMSSpamCollection']):
        archive = root / (name + '.zip')
        if not archive.exists():
            archive.write_bytes(urllib.request.urlopen(source['download'], timeout=60).read())
        data = archive.read_bytes()
        if hashlib.sha256(data).hexdigest() != source['zip_sha256']:
            raise ValueError('Public dataset checksum mismatch: ' + name)
        with zipfile.ZipFile(io.BytesIO(data)) as compressed:
            text = compressed.read(member).decode('utf8')
        if name == 'id':
            reader = csv.reader(io.StringIO(text)); next(reader)
            raw.extend((body, int(label != '0'), name) for body, label in reader)
        else:
            for line in text.splitlines():
                if '\t' in line:
                    label, body = line.split('\t', 1)
                    raw.append((body, int(label == 'spam'), name))
    unique = {}; conflicts = set()
    for row in raw:
        key = normalized(row[0])
        if key in unique and unique[key][1] != row[1]: conflicts.add(key)
        unique[key] = row
    rows = [r for key, r in unique.items() if key and key not in conflicts]
    texts = [r[0] for r in rows]
    # This character model only groups similar templates to prevent leakage.
    # Actual spam predictions use Gemma vectors exclusively.
    matrix = TfidfVectorizer(analyzer='char', ngram_range=(3, 5), min_df=2, max_features=12000).fit_transform([normalized(t) for t in texts])
    neighbors = NearestNeighbors(metric='cosine', algorithm='brute', n_jobs=1).fit(matrix).radius_neighbors(matrix, radius=.10, return_distance=False)
    parents = list(range(len(rows)))

    def find(index):
        while parents[index] != index:
            parents[index] = parents[parents[index]]
            index = parents[index]
        return index

    for index, near in enumerate(neighbors):
        for other in near: parents[find(int(other))] = find(index)
    groups = np.array([find(index) for index in range(len(rows))])
    strata = np.array([r[2] + str(r[1]) for r in rows])
    folds = list(StratifiedGroupKFold(n_splits=7, shuffle=True, random_state=719).split(texts, strata, groups))
    test = folds[0][1].tolist(); validation = folds[1][1].tolist()
    train = sorted(set(range(len(rows))) - set(test) - set(validation))
    encoded = '\n'.join(base64.b64encode(r[0].encode()).decode() for r in rows) + '\n'
    (root / 'rows.base64').write_text(encoded)
    metadata = dict(rows=rows, train=train, validation=validation, test=test, raw=len(raw), conflicts=len(conflicts), input_sha256=hashlib.sha256(encoded.encode()).hexdigest())
    (root / 'prepared.json').write_text(json.dumps(metadata))
    print(json.dumps(dict(rows=len(rows), train=len(train), validation=len(validation), test=len(test))))
    raise SystemExit(0)

if args.embeddings is None: parser.error('Use --prepare, then --embeddings with the Gemma export')
data = json.loads((root / 'prepared.json').read_text())
if hashlib.sha256((root / 'rows.base64').read_bytes()).hexdigest() != data['input_sha256']: raise ValueError('Prepared input changed after split')
vector_bytes = args.embeddings.read_bytes()
if vector_bytes[:8] != b'NUMIEMB1': raise ValueError('Not a Gemma embedding export')
count, dimensions = struct.unpack('>ii', vector_bytes[8:16])
if count != len(data['rows']) or dimensions != 768 or len(vector_bytes) != 16 + count * dimensions * 4: raise ValueError('Embedding dimensions or row count mismatch')
vectors = np.frombuffer(vector_bytes[16:], dtype='>f4').astype(np.float64).reshape(count, dimensions)
if not np.isfinite(vectors).all() or not np.allclose(np.linalg.norm(vectors, axis=1), 1, atol=1e-5): raise ValueError('Invalid or unnormalized Gemma embeddings')
y = np.array([row[1] for row in data['rows']]); langs = np.array([row[2] for row in data['rows']])
train, validation, test = (np.array(data[key]) for key in ['train', 'validation', 'test'])
head = LogisticRegression(C=64, max_iter=500, random_state=719).fit(vectors[train], y[train])
probs = head.predict_proba(vectors[validation])[:, 1]
thresholds = [float(t) for t in np.arange(.60, .991, .01) if precision_score(y[validation], probs >= t, zero_division=0) >= .99 and recall_score(y[validation], probs >= t) >= .50]
if not thresholds: raise RuntimeError('Gemma head did not meet validation precision/recall requirements')
threshold = min(thresholds)
report = dict(architecture='EmbeddingGemma 2 Text 270M INT4, 768d normalized chunk pooling, linear decision head C=64', raw=data['raw'], unique=count,
              conflicting_normalized_labels_removed=data['conflicts'], split_counts={key: len(data[key]) for key in ['train', 'validation', 'test']},
              threshold=round(threshold, 3), group_similarity_threshold=.90, frozen_training_split=True, private_sms_used_for_training=False,
              random_seed=719, license='CC-BY-SA-4.0', sources=sources, gemma_sha256=MODEL_SHA, runtime='LiteRT-LM 0.18.0',
              input_sha256=data['input_sha256'], embeddings_sha256=hashlib.sha256(vector_bytes).hexdigest(),
              preprocessing='GemmaTextInput.java: classification query prefix, 96-byte UTF-8 chunks, 3000 code point cap, 128 tokens, 768d L2-normalized sum')
for title, indices in [('validation', validation), ('test', test)]:
    predictions = head.predict_proba(vectors[indices])[:, 1] >= threshold
    report[title] = {}
    for language in ['all', 'id', 'en']:
        use = np.ones(len(indices), dtype=bool) if language == 'all' else langs[indices] == language
        yy = y[indices][use]; pred = predictions[use]
        report[title][language] = dict(count=len(yy), matrix=confusion_matrix(yy, pred).tolist(), precision=precision_score(yy, pred, zero_division=0), recall=recall_score(yy, pred))
args.output.mkdir(parents=True, exist_ok=True)
with (args.output / 'gemma-spam-head-v1.bin').open('wb') as output:
    output.write(b'NUMIGEM1'); output.write(struct.pack('>idd', dimensions, float(head.intercept_[0]), threshold))
    output.write(np.asarray(head.coef_[0], dtype='>f8').tobytes())
asset = (args.output / 'gemma-spam-head-v1.bin').read_bytes()
report.update(asset_sha256=hashlib.sha256(asset).hexdigest(), bytes=len(asset), evaluation_scope='Raw Gemma classifier before contact, allowlist, OTP and local protection rules')
(args.output / 'provenance.json').write_text(json.dumps(report, indent=2) + '\n')
# Independent sklearn probabilities exercise the Android port with real Gemma
# vectors, including examples near the decision threshold and both extremes.
test_probabilities = head.predict_proba(vectors[test])[:, 1]
near = np.argsort(np.abs(test_probabilities - threshold))[:15]
extremes = np.concatenate([np.argsort(test_probabilities)[:5], np.argsort(test_probabilities)[-5:]])
selected = list(dict.fromkeys([int(i) for i in np.concatenate([near, extremes])]))
for candidate in range(len(test)):
    if len(selected) >= 25: break
    if candidate not in selected: selected.append(candidate)
args.parity_output.parent.mkdir(parents=True, exist_ok=True)
args.parity_output.write_text(''.join(
    str(int(test[i])) + '\t' + format(float(test_probabilities[i]), '.17g') + '\t'
    + ','.join(format(float(value), '.9g') for value in vectors[test[i]]) + '\n'
    for i in selected))
print(json.dumps(report, indent=2))
