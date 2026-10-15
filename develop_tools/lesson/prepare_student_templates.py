"""Convert native with_heart cards to shared BAAS 720p portrait templates."""
import argparse
import csv
import io
from pathlib import Path
import zipfile

import cv2
import numpy as np


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('cards_zip', type=Path)
    parser.add_argument('heart_png', type=Path)
    parser.add_argument('--output', type=Path, default=(
        Path(__file__).resolve().parents[2] / 'src/images/lesson_students'))
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    selected = []
    with zipfile.ZipFile(args.cards_zip) as archive:
        rows = csv.DictReader(io.StringIO(archive.read('mapping.csv').decode('utf-8-sig')))
        files = set(archive.namelist())
        for row in rows:
            name = 'with_heart/' + row['file']
            if name not in files:  # Unreleased students are not enabled.
                continue
            rgba = cv2.imdecode(np.frombuffer(archive.read(name), np.uint8), cv2.IMREAD_UNCHANGED)
            if rgba is None or rgba.shape != (227, 279, 4):
                raise ValueError('Unexpected native card: ' + name)
            alpha = rgba[:, :, 3:4].astype(np.float32) / 255
            # Flatten BEFORE resizing: #dde3e5 in BGR order.
            flat = np.rint(rgba[:, :, :3] * alpha +
                           np.array([229, 227, 221], np.float32) * (1 - alpha)).astype(np.uint8)
            template = cv2.resize(flat, (71, 58), interpolation=cv2.INTER_AREA)
            cv2.imwrite(str(args.output / (row['id'] + '.png')), template)
            selected.append({k: row[k] for k in ('id', 'dev_name', 'jp_name', 'cn_name')})
    with (args.output / 'mapping.csv').open('w', encoding='utf-8', newline='') as f:
        writer = csv.DictWriter(f, fieldnames=['id', 'dev_name', 'jp_name', 'cn_name'])
        writer.writeheader()
        writer.writerows(selected)
    heart = cv2.imread(str(args.heart_png), cv2.IMREAD_UNCHANGED)
    if heart is None or heart.shape[2] != 4:
        raise ValueError('Expected an RGBA heart icon')
    # Same tint as the prefab's with_heart layer; retain alpha for screenshot compositing.
    heart[:, :, :3] = np.rint(heart[:, :, :3].astype(np.float32) *
                              np.array([248, 247, 244]) / 255).astype(np.uint8)
    heart = cv2.resize(heart, (29, 27), interpolation=cv2.INTER_AREA)
    cv2.imwrite(str(args.output / 'heart.png'), heart)
    print('Prepared', len(selected), 'lesson portrait templates')


if __name__ == '__main__':
    main()
