/*
 * DBeaver headless export command
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package org.jkiss.dbeaver.ext.exportnow;

/** A range of a script text: {@code length} characters from {@code offset}. */
public record TextRange(int offset, int length) {

  public boolean contains(int position) {
    return position >= offset && position < offset + length;
  }
}
