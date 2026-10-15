"""Schedule portrait matching; all coordinates and assets use 1280 x 720."""
import csv
import json
import logging
from functools import lru_cache
from pathlib import Path
import unicodedata

import cv2
import numpy as np

from core.image import compare_image_rgb


ASSET_DIR = Path(__file__).resolve().parents[1] / "src/images/lesson_students"
# Battle Hoshino has two combat forms; either portrait identifies the same student.
STUDENT_FORMS = {'10099': '10098'}


def normalize_name(name):
    return ''.join(c for c in unicodedata.normalize('NFKC', str(name)).casefold()
                   if c.isalnum())


@lru_cache(maxsize=1)
def load_students():
    logger = logging.getLogger(__name__)
    try:
        with (ASSET_DIR / 'mapping.csv').open(encoding='utf-8-sig', newline='') as f:
            rows = list(csv.DictReader(f))
    except OSError as error:
        logger.warning('Lesson student mapping unavailable: %s', error)
        return {}, {}, None
    templates, aliases = {}, {}
    for row in rows:
        portrait_id = row['id']
        student_id = STUDENT_FORMS.get(portrait_id, portrait_id)
        template = cv2.imread(str(ASSET_DIR / (portrait_id + '.png')))
        if template is None:
            logger.warning('Skipping missing lesson portrait: %s', portrait_id)
            continue
        templates[portrait_id] = template
        for value in row.values():
            if value:
                name = normalize_name(value)
                if aliases.setdefault(name, student_id) != student_id:
                    aliases[name] = None  # Ambiguous names require an explicit ID.
    heart = cv2.imread(str(ASSET_DIR / 'heart.png'), cv2.IMREAD_UNCHANGED)
    if heart is None or heart.shape != (27, 29, 4):
        logger.warning('Lesson heart overlay missing or invalid; specified students disabled')
        return {}, {}, None
    return templates, aliases, heart


@lru_cache(maxsize=1)
def english_student_names():
    from core.config.default_config import STATIC_DEFAULT_CONFIG
    return {normalize_name(row['Global_name']): normalize_name(row['JP_name'])
            for row in json.loads(STATIC_DEFAULT_CONFIG)['student_names']
            if row.get('Global_name') and row.get('JP_name')}


def resolve_student(name):
    aliases = load_students()[1]
    name = normalize_name(name)
    if name in aliases:
        return aliases[name]
    # English names are only a compatibility layer; the portrait mapping stays authoritative.
    return aliases.get(english_student_names().get(name))


def detect_students(frame, region, relationship_count):
    """Return IDs in the owned (pink-heart) slots, rejecting ambiguous matches."""
    if relationship_count <= 0:
        return []
    templates, _, heart = load_students()
    if not templates or heart is None:
        return []
    x, y, right, bottom = region
    # Copy only this block: other recognition and failure screenshots stay intact.
    area = frame[y:bottom, x:right].copy()
    alpha = heart[:, :, 3:4].astype(np.float32) / 255
    found = []
    for slot in range(3):
        # Calibrated card origin (4, 7), heart origin (41, 30), offset (-2, +1).
        hx, hy = 43 + 72 * slot, 38
        h, w = heart.shape[:2]
        roi = area[hy:hy + h, hx:hx + w]
        # Unowned portraits have no heart and must not become invite targets.
        # Same three sample points used by lesson.get_lesson_relationship_counts.
        b, g, r = frame[y + 45, x + 68 + 72 * slot]
        if not (223 <= int(r) <= 255 and 164 <= int(g) <= 224 and 190 <= int(b) <= 230):
            continue
        roi[:] = np.rint(heart[:, :, :3] * alpha + roi * (1 - alpha)).astype(np.uint8)
        patch = area[:, 72 * slot:72 * slot + 81]
        candidates = {}
        for portrait_id, template in templates.items():
            scores = cv2.matchTemplate(patch, template, cv2.TM_CCOEFF_NORMED)
            _, score, _, pos = cv2.minMaxLoc(scores)
            student_id = STUDENT_FORMS.get(portrait_id, portrait_id)
            if student_id not in candidates or score > candidates[student_id][0]:
                candidates[student_id] = (score, student_id, portrait_id, pos)
        ranking = sorted(candidates.values(), reverse=True)
        score, student_id, portrait_id, (px, py) = ranking[0]
        if score < 0.75 or (len(ranking) > 1 and score - ranking[1][0] < 0.1):
            continue
        template = templates[portrait_id]
        th, tw = template.shape[:2]
        if compare_image_rgb(template, patch[py:py + th, px:px + tw], rgb_diff=20):
            found.append(student_id)
        if len(found) >= relationship_count:
            break
    return found
