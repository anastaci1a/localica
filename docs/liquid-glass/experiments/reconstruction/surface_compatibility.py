"""Flat-front, single-back-surface geometric compatibility.

Orthographic propagation -z; front0, back-h(x,y), background-G, glass index n>1.
These local equations do not certify a closed solid, source coverage, shading,
or Apple geometry. Zero map curl alone is neither the required test nor proof.
"""
import numpy as np


def inverse_gradient(displacement, clearance, index):
    """Recover the required height gradient on the transmitted branch only."""
    if not np.isfinite(index) or index <= 1:
        raise ValueError('This inverse requires a finite index greater than one')
    v = np.asarray(displacement, float)
    s = np.asarray(clearance, float)
    m = np.sum(v * v, axis=-1)
    if np.any(~np.isfinite(v)) or np.any(~np.isfinite(s)) or np.any(s <= np.sqrt(m / (index * index - 1))):
        raise ValueError('Non-transmitted, critical or nonpositive-clearance branch')
    radius = np.sqrt(m + s * s)
    return v / (index * radius - s)[..., None]


def forward_displacement(gradient, clearance, index):
    """Exact Snell exit of vertical rays through a back height gradient."""
    if not np.isfinite(index) or index <= 1:
        raise ValueError('This comparator requires a finite index greater than one')
    q = np.asarray(gradient, float)
    s = np.asarray(clearance, float)
    if np.any(~np.isfinite(q)) or np.any(~np.isfinite(s)) or np.any(s <= 0):
        raise ValueError('Invalid gradient or clearance')
    cosine = 1 / np.sqrt(1 + np.sum(q * q, axis=-1))
    k = 1 - index * index * (1 - cosine * cosine)
    if np.any(k <= 0):
        raise ValueError('Critical ray or total internal reflection')
    factor = (index * cosine - np.sqrt(k)) * cosine
    descent = index - factor
    return q * (s * factor / descent)[..., None]


def compatibility_terms(displacement, dx, dy):
    """c=curl(v), M=|v|², K; derivatives belong to the same inverse map."""
    v, dx, dy = (np.asarray(a, float) for a in (displacement, dx, dy))
    c = dx[..., 1] - dy[..., 0]
    m = np.sum(v * v, axis=-1)
    k = v[..., 1] * np.sum(v * dx, axis=-1) - v[..., 0] * np.sum(v * dy, axis=-1)
    return c, m, k


def required_clearance(rho, magnitude_squared, index):
    """Necessary clearance for reliable nonzero curl; not a recovered surface.

    rho=K/c must exceed M=|v|². Near-zero c must be handled as unresolved by the
    caller, not divided through or declared physically impossible.
    """
    if not np.isfinite(index) or index <= 1:
        raise ValueError('This inverse requires a finite index greater than one')
    rho, m = np.broadcast_arrays(np.asarray(rho, float), np.asarray(magnitude_squared, float))
    if np.any(~np.isfinite(rho)) or np.any(~np.isfinite(m)) or np.any(m < 0) or np.any(rho <= m):
        raise ValueError('No strictly transmitted local compatibility branch')
    a = rho - m
    return np.sqrt((2 * index * index * a + m + np.sqrt(m * m + 4 * index * index * a * rho)) / (2 * (index * index - 1)))
