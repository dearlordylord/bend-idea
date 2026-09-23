package com.dearlordylord.bend.idea.model

/** Source identity, independent of any root-relative namespace or import spelling. */
final class FileId(val value: String, val canonical: Boolean):
  override def equals(other: Any): Boolean = other match
    case that: FileId => value == that.value && canonical == that.canonical
    case _ => false
  override def hashCode(): Int = 31 * value.hashCode + canonical.hashCode
  override def toString: String = s"FileId($value,$canonical)"
