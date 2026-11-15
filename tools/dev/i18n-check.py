#!/usr/bin/env python3
"""i18n-check.py [res dir] — every translation of the app's strings checked against the English (values/):

  missing / extra keys, placeholders that differ (%1$s, %d … a wrong one crashes the format at run time),
  plurals without the quantities the language needs (CLDR: Russian needs one/few/many/other), apostrophes and
  quotes aapt would choke on, text left in English (likely untranslated), and translations much longer than the
  English (a button or a chip may cut them short — checked on the headset with `aura.i18n_scan`).

Exit status 1 when anything that breaks the build or the app is found (missing keys, placeholders, plurals,
escaping); the English-looking and long ones are listed for a look but do not fail.

  python3 tools/dev/i18n-check.py            # app/src/main/res
  python3 tools/dev/i18n-check.py --long     # also list every long translation
"""
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..')
RES = next((a for a in sys.argv[1:] if not a.startswith('--')), os.path.join(ROOT, 'app', 'src', 'main', 'res'))
SHOW_LONG = '--long' in sys.argv

# The plural categories CLDR gives each language's cardinal numbers (integers; the app counts things).
PLURALS = {
    'en': {'one', 'other'}, 'zh': {'other'}, 'ja': {'other'}, 'ko': {'other'}, 'th': {'other'}, 'ms': {'other'},
    'de': {'one', 'other'}, 'nl': {'one', 'other'}, 'sv': {'one', 'other'}, 'da': {'one', 'other'},
    'nb': {'one', 'other'}, 'fi': {'one', 'other'}, 'el': {'one', 'other'}, 'tr': {'one', 'other'},
    'it': {'one', 'many', 'other'}, 'es': {'one', 'many', 'other'}, 'fr': {'one', 'many', 'other'},
    'pt': {'one', 'many', 'other'}, 'ro': {'one', 'few', 'other'},
    'ru': {'one', 'few', 'many', 'other'}, 'pl': {'one', 'few', 'many', 'other'},
    'cs': {'one', 'few', 'many', 'other'},
}
# Words that read the same in every language: names, formats, units.
SAME_EVERYWHERE = re.compile(
    r'^(Reality NAS Player|SMB|NFS|FTP|SFTP|WebDAV|DLNA|Jellyfin|Emby|Plex|TMDB|IMDb|OMDb|AniList|Bangumi|Jikan|MyAnimeList|'
    r'HDR|HDR10\+?|Dolby Vision|Dolby Atmos|4K|8K|1080p|720p|HEVC|H\.264|AV1|VP9|ASS|SRT|SSA|PGS|VobSub|mpv|Media3|'
    r'ExoPlayer|FFmpeg|OK|Wi-Fi|PICO|IPv4|IPv6|URL|ISO|BD|DVD|fps|kHz|Hz|GB|MB|KB|%[0-9$a-z.]+|[0-9.:×x%·/ +-]+)$',
    re.I)
PH = re.compile(r'%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[sdfxXc%]')


def lang_of(folder):
    q = folder[len('values-'):] if folder.startswith('values-') else 'en'
    if q.startswith('b+'):
        return q.split('+')[1]
    return q.split('-')[0]


def load(folder):
    strings, plurals = {}, {}
    d = os.path.join(RES, folder)
    for f in sorted(os.listdir(d)):
        if not (f.startswith('strings') and f.endswith('.xml')):
            continue
        root = ET.parse(os.path.join(d, f)).getroot()
        for e in root:
            name = e.get('name')
            if e.get('translatable') == 'false':
                continue
            if e.tag == 'string':
                strings[name] = (''.join(e.itertext()), f)
            elif e.tag == 'plurals':
                plurals[name] = ({i.get('quantity'): ''.join(i.itertext()) for i in e.findall('item')}, f)
    return strings, plurals


def raw_problems(folder):
    """Apostrophes and quotes as written in the file (ElementTree has already undone the escapes)."""
    out = []
    d = os.path.join(RES, folder)
    for f in sorted(os.listdir(d)):
        if not (f.startswith('strings') and f.endswith('.xml')):
            continue
        for n, line in enumerate(open(os.path.join(d, f), encoding='utf-8'), 1):
            m = re.search(r'<(string|item)\b[^>]*>(.*)</(string|item)>', line)
            if not m:
                continue
            body = m.group(2)
            if body.startswith('"') and body.endswith('"'):
                continue
            if re.search(r"(?<!\\)'", body):
                out.append(f'{f}:{n} unescaped apostrophe')
            if re.search(r'(?<!\\)"', body):
                out.append(f'{f}:{n} unescaped double quote')
    return out


def placeholders(s):
    return sorted(m.group(0) for m in PH.finditer(s) if m.group(0) != '%%')


def main():
    folders = sorted(f for f in os.listdir(RES) if f.startswith('values') and
                     any(x.startswith('strings') for x in os.listdir(os.path.join(RES, f))))
    base_s, base_p = load('values')
    fatal = 0
    print(f'English: {len(base_s)} strings, {len(base_p)} plurals')
    for folder in folders:
        if folder == 'values':
            continue
        lang = lang_of(folder)
        s, p = load(folder)
        problems, english, long = [], [], []
        for k in sorted(set(base_s) - set(s)):
            problems.append(f'missing {k} ({base_s[k][1]})')
        for k in sorted(set(base_p) - set(p)):
            problems.append(f'missing plurals {k} ({base_p[k][1]})')
        for k in sorted(set(s) - set(base_s)):
            problems.append(f'extra {k} ({s[k][1]})')
        for k, (text, f) in s.items():
            if k not in base_s:
                continue
            en = base_s[k][0]
            if placeholders(text) != placeholders(en):
                problems.append(f'{k}: placeholders {placeholders(text)} ≠ English {placeholders(en)}')
            if text == en and not SAME_EVERYWHERE.match(en.strip()) and re.search(r'[A-Za-z]{3}', en) \
                    and lang not in ('en',):
                english.append(k)
            if len(en) >= 6 and len(text) > 1.6 * len(en) and len(text) - len(en) > 8:
                long.append(f'{k}: {len(en)} → {len(text)} chars')
        need = PLURALS.get(lang)
        for k, (items, f) in p.items():
            if need and not need <= set(items):
                problems.append(f'plurals {k}: has {sorted(items)}, {lang} needs {sorted(need)}')
            for q, text in items.items():
                en_other = base_p.get(k, ({}, ''))[0].get('other', '')
                if placeholders(text) and placeholders(text) != placeholders(en_other):
                    problems.append(f'plurals {k}[{q}]: placeholders {placeholders(text)} ≠ {placeholders(en_other)}')
        problems += raw_problems(folder)
        fatal += len(problems)
        flag = '✗' if problems else '✓'
        print(f'{flag} {folder:18} {len(s):5} strings  {len(problems)} problems  {len(english)} still English  '
              f'{len(long)} long')
        for x in problems[:40]:
            print('    ' + x)
        if len(problems) > 40:
            print(f'    … {len(problems) - 40} more')
        if english and (len(english) <= 12 or SHOW_LONG):
            print('    English: ' + ', '.join(english))
        if SHOW_LONG:
            for x in long:
                print('    long ' + x)
    sys.exit(1 if fatal else 0)


if __name__ == '__main__':
    main()
