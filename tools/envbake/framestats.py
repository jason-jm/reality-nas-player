"""framestats.py <trace> : load into a perf session, print GPU/CPU frame stats and late/miss counts, close it."""
import json, subprocess, sys
def cli(*a):
    out = subprocess.run(['pico-cli', 'perf', *a], capture_output=True, text=True, timeout=600).stdout
    return json.loads(out[out.index('{'):])
import re
sid = sys.argv[2] if len(sys.argv) > 2 else re.search(r'Session: (\S+)', subprocess.run(['pico-cli', 'perf', 'trace', 'load', sys.argv[1], '--spatial-diagnose', 'true'], capture_output=True, text=True, timeout=900).stdout).group(1)
def q(sql): return cli('trace', 'query', '--session', sid, '--sql', sql)['data']['rows']
try:
    gpu = sorted(x['d'] / 1e6 for x in q("select dur d from OpenXRClientSpatialFrames where name='GPUTime'"))
    cpu = sorted(x['d'] / 1e6 for x in q("select dur d from OpenXRClientSpatialFrames where name='CPUTime'"))
    kinds = q("select substr(name,1,instr(name,' ')-1) k, count(*) n from OpenXRClientSpatialFrames where depth=0 group by k")
    tri = q("select max(cast(value as int)) m from counter c join counter_track t on c.track_id=t.id where t.name='Triangle Count'")
    freq = q("select max(cast(value as int)) m, avg(value) a from counter c join counter_track t on c.track_id=t.id where t.name='gpufreq'")
    pct = lambda a, p: a[min(len(a) - 1, int(len(a) * p))]
    print(json.dumps({
        'frames': len(gpu),
        'gpu_ms': {'avg': round(sum(gpu) / len(gpu), 2), 'p50': round(pct(gpu, .5), 2), 'p95': round(pct(gpu, .95), 2), 'p99': round(pct(gpu, .99), 2), 'max': round(gpu[-1], 2), 'over_11ms': sum(1 for g in gpu if g > 11.1)},
        'cpu_ms': {'avg': round(sum(cpu) / len(cpu), 2), 'p95': round(pct(cpu, .95), 2)},
        'frame_kinds': {k['k']: k['n'] for k in kinds},
        'max_triangles': tri[0]['m'], 'gpufreq_max': freq[0]['m'],
    }))
finally:
    subprocess.run(['pico-cli', 'perf', 'session', 'close', sid], capture_output=True)
