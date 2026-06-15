package com.zzypiper.module;

public sealed interface Outcome permits Outcome.Allow, Outcome.Deny {
    record Allow() implements Outcome {
    }

    record Deny(String reason) implements Outcome {
    }
}