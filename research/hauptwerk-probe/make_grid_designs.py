"""Writes the design files for the empirical X/Y pan table: the grid nodes, then held-out test
points, packed 33 per organ (spatial/designs/grid-NN.csv). Also writes spatial/designs/grid-points.csv
listing every point with its role (grid / test).

X >= 0 only (pan is antisymmetric in X, measured exactly); X = 0 is not measured (always centred).
Y has both signs; rows are dense near Y = 0, and Y = 0 is its own row (it does not follow its
neighbours). Test points are uniform random in the domain, seeded, never on a grid node.
"""
import csv
import random

X_NODES = [0.125, 0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 2.0, 2.5, 3.0, 3.5, 4.0]
Y_NODES = [-4.0, -3.0, -2.5, -2.0, -1.5, -1.0, -0.5, -0.25, -0.1, -0.05, 0.0,
           0.05, 0.1, 0.25, 0.5, 0.75, 1.0, 1.5, 2.0, 2.5, 3.0, 4.0, 5.0, 6.0, 8.0]
N_TEST = 63
PER_ORGAN = 33


def main():
    grid = [(x, y, 'grid') for y in Y_NODES for x in X_NODES]
    rng = random.Random(20261031)
    tests = []
    while len(tests) < N_TEST:
        x = round(rng.uniform(0.05, 4.0), 3)
        y = round(rng.uniform(-4.0, 8.0), 3)
        if (x, y) not in {(gx, gy) for gx, gy, _ in grid}:
            tests.append((x, y, 'test'))
    points = grid + tests
    with open('spatial/designs/grid-points.csv', 'w', newline='') as f:
        w = csv.writer(f)
        w.writerow(['organ', 'slot', 'x_m', 'y_m', 'z_m', 'role'])
        for i, (x, y, role) in enumerate(points):
            w.writerow([i // PER_ORGAN, i % PER_ORGAN, x, y, 0, role])
    organs = (len(points) + PER_ORGAN - 1) // PER_ORGAN
    for k in range(organs):
        chunk = points[k * PER_ORGAN:(k + 1) * PER_ORGAN]
        while len(chunk) < PER_ORGAN:   # pad the last organ with centre points (known: p = 0)
            chunk.append((0.0, 1.0, 'pad'))
        with open(f'spatial/designs/grid-{k:02d}.csv', 'w', newline='') as f:
            f.write('x_m,y_m,z_m\n' + ''.join(f'{x},{y},0\n' for x, y, _ in chunk))
    print(f'{len(grid)} grid nodes + {len(tests)} test points = {len(points)} -> {organs} organs')


main()
