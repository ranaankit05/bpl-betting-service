package com.bally.bpl.service;

import java.util.List;
import java.util.function.Function;

public record PageResult<T>(List<T> items, long total) {

    public <R> PageResult<R> map(Function<T, R> mapper) {
        return new PageResult<>(items.stream().map(mapper).toList(), total);
    }
}
