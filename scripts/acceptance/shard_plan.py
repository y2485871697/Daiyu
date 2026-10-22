"""Duration-weighted, whole-class LPT; estimates never supply acceptance evidence."""
import hashlib
import json
from pathlib import Path
import statistics


DURATIONS = Path('scripts/acceptance/instrumentation-durations.json')


def allocate(classes, shard_count, root=Path('.')):
    path = root / DURATIONS
    measured = {}
    digest = None
    if path.exists():
        raw = path.read_bytes()
        digest = hashlib.sha256(raw).hexdigest()
        data = json.loads(raw)
        if data['schema_version'] != 1 or not isinstance(data['classes'], dict):
            raise ValueError('Invalid instrumentation duration data')
        for name, row in data['classes'].items():
            if (type(row['tests']) is not int or row['tests'] <= 0 or
                    type(row['milliseconds']) is not int or row['milliseconds'] <= 0):
                raise ValueError('Invalid class duration')
            source = Path(row['source_path'])
            if source.is_absolute() or '..' in source.parts or not source.parts[:3] == ('app', 'src', 'androidTest'):
                raise ValueError('Invalid duration source path')
            if name in classes and row['tests'] == classes[name] and (root / source).is_file():
                if hashlib.sha256((root / source).read_bytes()).hexdigest() == row['source_sha256']:
                    measured[name] = row['milliseconds']
    # Missing or edited classes remain in the inventory with a method-count estimate.
    unit = max(1, round(statistics.median(measured[name] / classes[name] for name in measured))) if measured else 1
    weights = {name: measured.get(name, count * unit) for name, count in classes.items()}
    groups = [{} for _ in range(shard_count)]
    loads = [0] * shard_count
    for name in sorted(classes, key=lambda name: (-weights[name], name)):
        target = min(range(shard_count), key=lambda index: (loads[index], index))
        groups[target][name] = classes[name]
        loads[target] += weights[name]
    receipt = dict(schema_version=1, strategy='duration_lpt' if measured else 'method_count_lpt',
                   duration_data_sha256=digest, measured_classes=sorted(measured),
                   fallback_classes=sorted(set(classes) - measured.keys()),
                   fallback_ms_per_test=unit, estimated_shard_ms=loads,
                   estimates_only=True, formal_acceptance=False)
    return groups, receipt
