package com.nova.shared.security.jwt;

@FunctionalInterface
public interface TokenIdGenerator {

    String generate();
}
