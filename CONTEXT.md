# Bend source and checking

Bend programs are edited as source files and checked in the context of a selected root. Source relationships and compiler judgments describe different aspects of the same program.

## Language

**Law**:
A declared type specification that can be filled by a definition. A law need not be an equality theorem and its presence does not establish a proof.
_Avoid_: Proven theorem

**Fill**:
A definition that supplies the implementation of a law. Different roots can use different fills of the same law.
_Avoid_: Verified proof

**Source symbol**:
A named binding or declaration identified at its source, independently of the aliases through which other files refer to it. Datatypes and constructors remain distinct even when their spellings coincide.

**Logical law symbol**:
The relationship between a law and its candidate filling definitions, retaining each separate source declaration and its parameter bindings.

**Import alias**:
A name local to the importing file that qualifies declarations from a directly imported file. Import aliases are not re-exported.

**Literal dotted name**:
A declaration name whose dots are part of its spelling, such as `U32.add`. It does not imply nested modules or receiver fields.

**Base**:
The Bend library loaded into the empty namespace, including when loaded through a dependency.

**Root**:
The entry source file that determines a loaded program and its checking context. An open editor file and the selected proof root can differ.

**Loaded graph**:
The source files and ordered import relationships reachable from a root, together with their compiler namespaces.

**Book**:
The compiler's loaded declarations and their checking order.

**Source snapshot**:
An immutable capture of a root's source graph and relevant loading conditions, used to associate a compiler result with the source that produced it.

**Hole**:
An unfinished source term, including a named hole or `?TODO`. Its presence is distinct from the availability of compiler-provided goal information.

**Goal**:
The expected type at a supported proof location, together with the local context supplied by the compiler.

**Quantity**:
The erased, affine or reusable classification of a binder. Affine does not require every value to be used.

**Template**:
A leading compile-time syntax argument.

**Motive**:
A rewrite proposition with placeholders bound within that proposition.
