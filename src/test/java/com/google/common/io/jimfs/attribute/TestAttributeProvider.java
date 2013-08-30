/*
 * Copyright 2013 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.common.io.jimfs.attribute;

import com.google.common.collect.ImmutableSet;
import com.google.common.io.jimfs.file.File;
import com.google.common.io.jimfs.file.FileProvider;

import java.io.IOException;
import java.nio.file.attribute.FileTime;

/**
 * @author Colin Decker
 */
public class TestAttributeProvider extends AbstractAttributeProvider
    implements AttributeViewProvider<TestAttributeView>, AttributeReader<TestAttributes> {

  private static final AttributeSpec FOO = AttributeSpec.unsettable("foo", String.class);
  private static final AttributeSpec BAR = AttributeSpec.settable("bar", Long.class);
  private static final AttributeSpec BAZ = AttributeSpec.settableOnCreate("baz", Integer.class);

  public TestAttributeProvider() {
    super(ImmutableSet.of(FOO, BAR, BAZ));
  }

  @Override
  public String name() {
    return "test";
  }

  @Override
  public ImmutableSet<String> inherits() {
    return ImmutableSet.of("basic");
  }

  @Override
  public ImmutableSet<Class<?>> acceptedTypes(String attribute) {
    if (attribute.equals("bar")) {
      return ImmutableSet.<Class<?>>of(Number.class);
    }
    return super.acceptedTypes(attribute);
  }

  @Override
  public void set(File file, String attribute, Object value) {
    if (attribute.equals("bar")) {
      file.setAttribute("test:bar", ((Number) value).longValue());
    } else {
      super.set(file, attribute, value);
    }
  }

  @Override
  public void setInitial(File file) {
    file.setAttribute("test:bar", 0L);
    file.setAttribute("test:baz", 1);
  }

  @Override
  public Object get(File file, String attribute) {
    if (attribute.equals("foo")) {
      return "hello";
    }
    return super.get(file, attribute);
  }

  @Override
  public Class<TestAttributes> attributesType() {
    return TestAttributes.class;
  }

  @Override
  public TestAttributes read(File file) {
    try {
      return getView(FileProvider.ofFile(file)).readAttributes();
    } catch (IOException unexpected) {
      throw new AssertionError(unexpected);
    }
  }

  @Override
  public Class<TestAttributeView> viewType() {
    return TestAttributeView.class;
  }

  @Override
  public TestAttributeView getView(FileProvider fileProvider) {
    return new View(this, fileProvider);
  }

  public static final class View extends AbstractAttributeView implements TestAttributeView {

    public View(AttributeProvider attributeProvider, FileProvider fileProvider) {
      super(attributeProvider, fileProvider);
    }

    @Override
    public Attributes readAttributes() throws IOException {
      return new Attributes(this);
    }

    @Override
    public void setBar(long bar) throws IOException {
      set("bar", bar);
    }

    @Override
    public void setBaz(int baz) throws IOException {
      set("baz", baz);
    }
  }

  public static final class Attributes implements TestAttributes {

    private final String foo;
    private final Long bar;
    private final Integer baz;

    public Attributes(View view) throws IOException {
      this.foo = view.get("foo");
      this.bar = view.get("bar");
      this.baz = view.get("baz");
    }

    @Override
    public String foo() {
      return foo;
    }

    @Override
    public long bar() {
      return bar;
    }

    @Override
    public int baz() {
      return baz;
    }

    // BasicFileAttributes is just implemented here because readAttributes requires a subtype of
    // BasicFileAttributes -- methods are not implemented

    @Override
    public FileTime lastModifiedTime() {
      return null;
    }

    @Override
    public FileTime lastAccessTime() {
      return null;
    }

    @Override
    public FileTime creationTime() {
      return null;
    }

    @Override
    public boolean isRegularFile() {
      return false;
    }

    @Override
    public boolean isDirectory() {
      return false;
    }

    @Override
    public boolean isSymbolicLink() {
      return false;
    }

    @Override
    public boolean isOther() {
      return false;
    }

    @Override
    public long size() {
      return 0;
    }

    @Override
    public Object fileKey() {
      return null;
    }
  }
}
