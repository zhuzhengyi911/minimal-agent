---
name: code_review
description: Perform structured code review covering correctness, readability, error handling, and security
---

# Code Review Guidelines

When asked to review code, follow these steps in order:

## Step 1 — Structure & Readability
- Is the code well-organized and easy to follow?
- Are names (variables, methods, classes) meaningful and consistent?
- Are there any unnecessary or missing critical comments?

## Step 2 — Correctness & Safety
- Could any operation throw a NullPointerException or similar runtime error?
- Are there unchecked assumptions about input data?
- Are boundary conditions (empty collections, zero, negative values) handled?

## Step 3 — Error Handling
- Are exceptions caught and handled properly, or are they swallowed silently?
- Are resources (streams, connections, files) properly closed after use?

## Step 4 — Design
- Does each class/method have a single, clear responsibility?
- Are there obvious code smells: magic numbers, deep nesting, duplicated logic, public mutable state?

## Output Format

Produce a structured report with the following sections:

**Summary**: Overall assessment in 1–2 sentences.

**Issues**: A numbered list. Each issue must include:
- Severity: 🔴 Critical / 🟡 Warning / 🔵 Suggestion
- Location: method name or line reference
- Description: what is wrong and why it matters
- Fix: a concrete, actionable suggestion

**Verdict**: `APPROVE` or `REQUEST_CHANGES`

---

> If the code under review is Java, call `load_skill` with name `code_review/java` to load
> the Java-specific checklist **before** writing your report. Apply both this general checklist
> and the Java-specific one together.
