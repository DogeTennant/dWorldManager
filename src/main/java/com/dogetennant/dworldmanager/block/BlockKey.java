package com.dogetennant.dworldmanager.block;

/** Coordinate key for the in-memory frozen-block cache checked on every block break. */
public record BlockKey(int x, int y, int z) {
}
