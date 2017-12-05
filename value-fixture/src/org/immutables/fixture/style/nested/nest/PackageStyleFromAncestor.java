/*
   Copyright 2015 Immutables Authors and Contributors

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */
package org.immutables.fixture.style.nested.nest;

import org.immutables.value.Value;

/**
 * This compilation test checks application of parent package's style.
 */
interface PackageStyleFromAncestor {

  @Value.Immutable
  interface Baj {
    int a();
  }

  /** Generated API dictated by parent package's style. */
  @SuppressWarnings("CheckReturnValue")
  static void use() {
    ImBaj.builder().a(1).build().copyWithA(2);
  }
}
