---
name: code_review/java
description: Java-specific checks for null safety, generics, resource management, and concurrency
---

# Java-Specific Review Checklist

Apply these checks **in addition to** the general `code_review` guidelines.

## Null Safety
- Every parameter that could be null must be checked before dereferencing
- Every return value that could be null must be checked before chaining `.method()` calls
- Prefer `Optional<T>` over returning `null` from methods

## Exception Handling
- No empty `catch` blocks — at minimum, log the exception
- Checked exceptions must not be silently swallowed
- Exception messages must be descriptive enough to diagnose the problem

## Resource Management
- Every `Closeable` (InputStream, OutputStream, Connection, ResultSet, etc.) must be opened
  inside a `try-with-resources` block
- Manual `finally` blocks for closing resources are a warning sign

## Generics & Type Safety
- No raw types: use `List<String>` not `List`, `Map<K,V>` not `Map`
- Unchecked casts must be justified with a comment
- Avoid `@SuppressWarnings("unchecked")` without explanation

## Code Quality
- Magic numbers must be extracted to named constants (`static final`)
- Public mutable fields are forbidden — expose state via getters/setters
- Methods longer than ~30 lines should be considered for extraction

## Concurrency (when applicable)
- Shared mutable state accessed from multiple threads must be synchronized
  or use thread-safe types (`AtomicInteger`, `ConcurrentHashMap`, etc.)
- Non-thread-safe collections (`ArrayList`, `HashMap`) must not be shared across threads
  without synchronization

Merge the findings from this checklist with the general review, and format everything
using the report structure defined in the `code_review` skill.
