"""Second grid pass: extra X columns where validation showed the largest errors (small X, where p
rises steeply). Writes spatial/designs/grid-11.csv .. (33 points per organ, padded with known
centre points) and spatial/designs/grid-extra-points.csv. The first pass (make_grid_designs.py)
and its held-out test points are unchanged.
"""
import csv

EXTRA_X = [0.0625, 0.1875, 0.375, 0.625, 0.875]
PER_ORGAN = 33
FIRST = 11


def main():
    ns = {}
    exec(open('make_grid_designs.py').read().split('def main')[0], ns)
    points = [(x, y) for y in ns['Y_NODES'] for x in EXTRA_X]
    organs = (len(points) + PER_ORGAN - 1) // PER_ORGAN
    with open('spatial/designs/grid-extra-points.csv', 'w', newline='') as f:
        w = csv.writer(f)
        w.writerow(['organ', 'slot', 'x_m', 'y_m', 'z_m'])
        for i, (x, y) in enumerate(points):
            w.writerow([FIRST + i // PER_ORGAN, i % PER_ORGAN, x, y, 0])
    for k in range(organs):
        chunk = points[k * PER_ORGAN:(k + 1) * PER_ORGAN]
        chunk += [(0.0, 1.0)] * (PER_ORGAN - len(chunk))
        with open(f'spatial/designs/grid-{FIRST + k:02d}.csv', 'w', newline='') as f:
            f.write('x_m,y_m,z_m\n' + ''.join(f'{x},{y},0\n' for x, y in chunk))
    print(f'{len(points)} extra grid nodes -> organs {FIRST}..{FIRST + organs - 1}')


main()
