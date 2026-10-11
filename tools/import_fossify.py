"""Import pinned Fossify source trees as Android libraries, isolating Phone resources."""
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def import_source(name, source):
    target = ROOT / 'android' / name
    shutil.copytree(source / 'app' / 'src' / 'main', target / 'src' / 'main')
    shutil.copyfile(source / 'LICENSE', target / 'LICENSE')
    manifest = target / 'src/main/AndroidManifest.xml'
    ET.register_namespace('android', 'http://schemas.android.com/apk/res/android')
    ET.register_namespace('tools', 'http://schemas.android.com/tools')
    tree = ET.parse(manifest)
    app = tree.getroot().find('application')
    for attribute in list(app.attrib):
        del app.attrib[attribute]
    for alias in list(app.findall('activity-alias')):
        if name == 'phone':
            app.remove(alias)
        else:
            key = '{http://schemas.android.com/apk/res/android}name'
            alias.set(key, '${applicationId}' + alias.get(key))
            key = '{http://schemas.android.com/apk/res/android}targetActivity'
            alias.set(key, 'org.fossify.messages' + alias.get(key))
    for component in app:
        key = '{http://schemas.android.com/apk/res/android}name'
        if component.get(key, '').startswith('.'):
            component.set(key, 'org.fossify.' + name + component.get(key))
        key = '{http://schemas.android.com/apk/res/android}parentActivityName'
        if component.get(key, '').startswith('.'):
            component.set(key, 'org.fossify.' + name + component.get(key))
    tree.write(manifest, encoding='utf-8', xml_declaration=True)
    commit = subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip()
    (target / 'upstream.json').write_text(json.dumps({
        'repository': 'https://github.com/FossifyOrg/' + name.title(),
        'commit': commit, 'license': 'GPL-3.0',
        'changes': 'Numi navigation, connector hooks, application integration' +
            (', and resource prefixes' if name == 'phone' else '')
    }, indent=2) + '\n')
    if name == 'phone':
        isolate_resources(target)


def isolate_resources(target):
    res = target / 'src/main/res'
    names = set()
    layouts = {}
    for path in res.rglob('*'):
        if not path.is_file():
            continue
        kind = path.parent.name.split('-')[0]
        if kind == 'values' and path.suffix == '.xml':
            for entry in ET.parse(path).getroot():
                kind_entry = entry.get('type', entry.tag)
                if entry.get('name') and kind_entry not in ('id', 'attr', 'declare-styleable'):
                    names.add((kind_entry, entry.get('name')))
        elif kind != 'res':
            names.add((kind, path.name.split('.')[0]))
            if kind == 'layout':
                cls = ''.join(part.title() for part in path.stem.split('_')) + 'Binding'
                layouts[cls] = 'NumiPhone' + cls
    for path in target.rglob('*'):
        if not path.is_file() or path.suffix not in ('.kt', '.xml'):
            continue
        text = path.read_text()
        text = re.sub(r'(?<![\w.])R\.(\w+)\.(\w+)',
            lambda m: 'R.' + m[1] + '.numi_phone_' + m[2] if (m[1], m[2]) in names else m[0], text)
        text = re.sub(r'@(?:\+)?(\w+)/([\w.]+)',
            lambda m: '@' + m[1] + '/numi_phone_' + m[2] if (m[1], m[2]) in names else m[0], text)
        if path.parent.name.startswith('values'):
            text = re.sub(r'(<(?:string|style|color|dimen|integer|bool|array|string-array|plurals)\s+name=")([^"]+)',
                lambda m: m[1] + 'numi_phone_' + m[2], text)
            text = re.sub(r'(parent=")([^"]+)',
                lambda m: m[1] + 'numi_phone_' + m[2] if ('style', m[2]) in names else m[0], text)
        for old, new in layouts.items():
            text = re.sub(r'\b' + old + r'\b', new, text)
        path.write_text(text)
    for path in list(res.rglob('*')):
        if path.is_file():
            kind = path.parent.name.split('-')[0]
            if kind not in ('values', 'res'):
                path.rename(path.with_name('numi_phone_' + path.name))


if __name__ == '__main__':
    import_source('messages', Path(sys.argv[1]))
    import_source('phone', Path(sys.argv[2]))
    shutil.copyfile(ROOT / 'android/messages/LICENSE', ROOT / 'LICENSE')
