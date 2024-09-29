/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 *
 */

#ifndef SHARE_OPTO_MEMPOINTER_HPP
#define SHARE_OPTO_MEMPOINTER_HPP

#include "opto/memnode.hpp"
#include "opto/noOverflowInt.hpp"

// The MemPointer is a shared facility to parse pointers and check the aliasing of pointers,
// e.g. checking if two stores are adjacent.
//
// -----------------------------------------------------------------------------------------
//
// MemPointerDecomposedForm:
//   When the pointer is parsed, it is decomposed into a constant and a sum of summands:
//
//     pointer = con + sum(summands)
//
//   Where each summand_i in summands has the form:
//
//     summand_i = scale_i * variable_i
//
//   Hence, the full decomposed form is:
//
//     pointer = con + sum_i(scale_i * variable_i)
//
//   On 64bit systems, this decomposed form is computed with long-add/mul, on 32bit systems
//   it is computed with int-add/mul.
//
// MemPointerAliasing:
//   The decomposed form allows us to determine the aliasing between two pointers easily. For
//   example, if two pointers are identical, except for their constant:
//
//     pointer1 = con1 + sum(summands)
//     pointer2 = con2 + sum(summands)
//
//   then we can easily compute the distance between the pointers (distance = con2 - con1),
//   and determine if they are adjacent.
//
// MemPointerDecomposedFormParser:
//   Any pointer can be parsed into this (default / trivial) decomposed form:
//
//     pointer = 0   + 1     * pointer
//               con   scale
//
//   However, this is not particularly useful to compute aliasing. We would like to decompose
//   the pointer as far as possible, i.e. extract as many summands and add up the constants to
//   a single constant.
//
//   Example (normal int-array access):
//     pointer1 = array[i + 0] = array_base + array_int_base_offset + 4L * ConvI2L(i + 0)
//     pointer2 = array[i + 1] = array_base + array_int_base_offset + 4L * ConvI2L(i + 1)
//
//     At first, computing aliasing is difficult because the distance is hidden inside the
//     ConvI2L. we can convert this (with array_int_base_offset = 16) into these decomposed forms:
//
//     pointer1 = 16L + 1L * array_base + 4L * i
//     pointer2 = 20L + 1L * array_base + 4L * i
//
//     This allows us to easily see that these two pointers are adjacent (distance = 4).
//
//   Hence, in MemPointerDecomposedFormParser::parse_decomposed_form, we start with the pointer as
//   a trivial summand. A summand can either be decomposed further or it is terminal (cannot
//   be decomposed further). We decompose the summands recursively until all remaining summands
//   are terminal, see MemPointerDecomposedFormParser::parse_sub_expression. This effectively parses
//   the pointer expression recursively.
//
// -----------------------------------------------------------------------------------------
//
//   We have to be careful on 64bit systems with ConvI2L: decomposing its input is not
//   correct in general, overflows may not be preserved in the decomposed form:
//
//     AddI:     ConvI2L(a +  b)    != ConvI2L(a) +  ConvI2L(b)
//     SubI:     ConvI2L(a -  b)    != ConvI2L(a) -  ConvI2L(b)
//     MulI:     ConvI2L(a *  conI) != ConvI2L(a) *  ConvI2L(conI)
//     LShiftI:  ConvI2L(a << conI) != ConvI2L(a) << ConvI2L(conI)
//
//   If we want to prove the correctness of MemPointerAliasing, we need some guarantees,
//   that the MemPointers adequately represent the underlying pointers, such that we can
//   compute the aliasing based on the summands and constants.
//
// -----------------------------------------------------------------------------------------
//
//   Below, we will formulate a "MemPointer Lemma" that helps us to prove the correctness of
//   the MemPointerAliasing computations. To prove the "MemPointer Lemma", we need to define
//   the idea of a "safe decomposition", and then prove that all the decompositions we apply
//   are such "safe decompositions".
//
//
//  Definition: Safe decomposition (from some mp_i to mp_{i+1})
//    We decompose summand in:
//      mp_i     = con + summand                     + sum(other_summands)
//    Resulting in:      +-------------------------+
//      mp_{i+1} = con + dec_con + sum(dec_summands) + sum(other_summands)
//               = new_con + sum(new_summands)
//
//    We call a decomposition safe if either:
//      SAFE1) No matter the values of the summand variables:
//               mp_i = mp_{i+1}
//
//      SAFE2) The pointer is on an array with a known array_element_size_in_bytes,
//             and there is an integer x, such that:
//               mp_i = mp_{i+1} + x * array_element_size_in_bytes * 2^32
//
//             Note: if "x = 0", we have "mp1 = mp2", and if "x != 0", then mp1 and mp2
//                   have a distance at least twice as large as the array size, and so
//                   at least one of mp1 or mp2 must be out of bounds of the array.
//
//    Note: MemPointerDecomposedFormParser::is_safe_to_decompose_op checks that all
//          decompositions we apply are safe.
//
//
//  MemPointer Lemma:
//    Given two pointers p1 and p2, and their respective MemPointers mp1 and mp2.
//    If these conditions hold:
//      S1) Both p1 and p2 are within the bounds of the same memory object.
//      S2) The constants do not differ too much: abs(mp1.con - mp2.con) < 2^31
//      S3) All summands of mp1 and mp2 are identical.
//
//    Then the ponter difference between p1 and p2 is identical to the difference between
//    mp1 and mp2:
//      p1 - p2 = mp1 - mp2
//
//    Note: MemPointerDecomposedForm::get_aliasing_with relies on this MemPointer Lemma to
//          prove the correctness of its aliasing computation between two MemPointers.
//
//
//  Proof of the "MemPointer Lemma":
//    Case 0: no decompositions were used:
//      mp1 = 0 + 1 * p1 = p1
//      mp2 = 0 + 1 * p2 = p2
//      =>
//      p1 - p2 = mp1 - mp2
//
//    Case 1: only decompositions of type (SAFE1) were used:
//      We make an induction proof over the decompositions from p1 to mp1, starting with
//      the trivial decompoisition:
//        mp1_0 = 0 + 1 * p1 = p1
//      and then for the i'th decomposition, we know that
//        mp1_i = mp1_{i+1}
//      and hence, if mp1 was decomposed with n decompositions from p1:
//        p1 = mp1_0 = mp1_i = mp1_n = mp1
//      The analogue can be proven for p2 and mp2:
//        p2 = mp2
//
//      p1 = mp1
//      p2 = mp2
//      =>
//      p1 - p2 = mp1 - mp2
//
//    Case 2: decompositions of type (SAFE2) were used, and possibly also decompositions of
//            type (SAFE1).
//       Given we have (SAFE2) decompositions, we know that we are operating on an array of
//       known array_element_size_in_bytes. We can weaken the guarantees from (SAFE1)
//       decompositions to the same guarantee as (SAFE2) decompositions, hence all applied
//       decompositions satisfy:
//         mp1_i = mp1_{i+1} + x1_i * array_element_size_in_bytes * 2^32
//       where x_i = 0 for (SAFE1) decompositions.
//
//      We make an induction proof over the decompositions from p1 to mp1, starting with
//      the trivial decompoisition:
//        mp1_0 = 0 + 1 * p1 = p1
//      and then for the i'th decomposition, we know that
//        mp1_i = mp1_{i+1} + x1_i * array_element_size_in_bytes * 2^32
//      and hence, if mp1 was decomposed with n decompositions from p1:
//        p1 = mp1 + x1 * array_element_size_in_bytes * 2^32
//      where x1 = sum(x1_i).
//      The analogue can be proven for p2 and mp2:
//        p2 = mp2 + x2 * array_element_size_in_bytes * 2^32
//
//      And hence, there must be an x, such that:
//        p1 - p2 = mp1 - mp2 + x * array_element_size_in_bytes * 2^32
//
//      If "x = 0", then it follows:
//        p1 - p2 = mp1 - mp2
//
//      If "x != 0", then:
//        abs(p1 - p2) =  abs(mp1 - mp2 + x * array_element_size_in_bytes * 2^32)
//                     >= abs(x * array_element_size_in_bytes * 2^32) - abs(mp1 - mp2)
//                            -- apply x != 0 --
//                     >= array_element_size_in_bytes * 2^32          - abs(mp1 - mp2)
//                                                               -- apply S2 and S3 --
//                     >  array_element_size_in_bytes * 2^32          - 2^31
//                     >= array_element_size_in_bytes * 2^31
//                     >= max_possible_array_size_in_bytes
//                     >= array_size_in_bytes
//
//        Thus we get a contradiction: p1 and p2 have a distance greater than the array
//        size, and hence at least one of the two must be out of bounds. But condition S1
//        of the MemPointer Lemma requires that both p1 and p2 are both in bounds of the
//        same memory object.

#ifndef PRODUCT
class TraceMemPointer : public StackObj {
private:
  const bool _is_trace_pointer;
  const bool _is_trace_aliasing;
  const bool _is_trace_adjacency;

public:
  TraceMemPointer(const bool is_trace_pointer,
                  const bool is_trace_aliasing,
                  const bool is_trace_adjacency) :
    _is_trace_pointer(  is_trace_pointer),
    _is_trace_aliasing( is_trace_aliasing),
    _is_trace_adjacency(is_trace_adjacency)
    {}

  bool is_trace_pointer()   const { return _is_trace_pointer; }
  bool is_trace_aliasing()  const { return _is_trace_aliasing; }
  bool is_trace_adjacency() const { return _is_trace_adjacency; }
};
#endif

// Class to represent aliasing between two MemPointer.
class MemPointerAliasing {
public:
  enum Aliasing {
    Unknown, // Distance unknown.
             //   Example: two "int[]" with different variable index offsets.
             //            e.g. "array[i]  vs  array[j]".
             //            e.g. "array1[i] vs  array2[j]".
    Always}; // Constant distance = p1 - p2.
             //   Example: The same address expression, except for a constant offset
             //            e.g. "array[i]  vs  array[i+1]".
private:
  const Aliasing _aliasing;
  const jint _distance;

  MemPointerAliasing(const Aliasing aliasing, const jint distance) :
    _aliasing(aliasing),
    _distance(distance)
  {
    const jint max_distance = 1 << 30;
    assert(_distance < max_distance && _distance > -max_distance, "safe distance");
  }

public:
  MemPointerAliasing() : MemPointerAliasing(Unknown, 0) {}

  static MemPointerAliasing make_unknown() {
    return MemPointerAliasing();
  }

  static MemPointerAliasing make_always(const jint distance) {
    return MemPointerAliasing(Always, distance);
  }

  // Use case: exact aliasing and adjacency.
  bool is_always_at_distance(const jint distance) const {
    return _aliasing == Always && _distance == distance;
  }

#ifndef PRODUCT
  void print_on(outputStream* st) const {
    switch(_aliasing) {
      case Unknown: st->print("Unknown");               break;
      case Always:  st->print("Always(%d)", _distance); break;
      default: ShouldNotReachHere();
    }
  }
#endif
};

// Summand of a MemPointerDecomposedForm:
//
//   summand = scale * variable
//
class MemPointerSummand : public StackObj {
private:
  Node* _variable;
  NoOverflowInt _scale;

public:
  MemPointerSummand() :
      _variable(nullptr),
      _scale(NoOverflowInt::make_NaN()) {}
  MemPointerSummand(Node* variable, const NoOverflowInt scale) :
      _variable(variable),
      _scale(scale)
  {
    assert(_variable != nullptr, "must have variable");
    assert(!_scale.is_zero(), "non-zero scale");
  }

  Node* variable() const { return _variable; }
  NoOverflowInt scale() const { return _scale; }

  static int cmp_for_sort(MemPointerSummand* p1, MemPointerSummand* p2) {
    if (p1->variable() == nullptr) {
      return (p2->variable() == nullptr) ? 0 : 1;
    } else if (p2->variable() == nullptr) {
      return -1;
    }

    return p1->variable()->_idx - p2->variable()->_idx;
  }

  friend bool operator==(const MemPointerSummand a, const MemPointerSummand b) {
    // Both "null" -> equal.
    if (a.variable() == nullptr && b.variable() == nullptr) { return true; }

    // Same variable and scale?
    if (a.variable() != b.variable()) { return false; }
    return a.scale() == b.scale();
  }

  friend bool operator!=(const MemPointerSummand a, const MemPointerSummand b) {
    return !(a == b);
  }

#ifndef PRODUCT
  void print_on(outputStream* st) const {
    st->print("Summand[");
    _scale.print_on(st);
    tty->print(" * [%d %s]]", _variable->_idx, _variable->Name());
  }
#endif
};

// Decomposed form of the pointer sub-expression of "pointer".
//
//   pointer = con + sum(summands)
//
class MemPointerDecomposedForm : public StackObj {
private:
  // We limit the number of summands to 10. Usually, a pointer contains a base pointer
  // (e.g. array pointer or null for native memory) and a few variables. For example:
  //
  //   array[j]                      ->  array_base + j + con              -> 2 summands
  //   nativeMemorySegment.get(j)    ->  null + address + offset + j + con -> 3 summands
  //
  static const int SUMMANDS_SIZE = 10;

  Node* _pointer; // pointer node associated with this (sub)pointer

  MemPointerSummand _summands[SUMMANDS_SIZE];
  NoOverflowInt _con;

public:
  // Empty
  MemPointerDecomposedForm() : _pointer(nullptr), _con(NoOverflowInt::make_NaN()) {}
  // Default / trivial: pointer = 0 + 1 * pointer
  MemPointerDecomposedForm(Node* pointer) : _pointer(pointer), _con(NoOverflowInt(0)) {
    assert(pointer != nullptr, "pointer must be non-null");
    _summands[0] = MemPointerSummand(pointer, NoOverflowInt(1));
  }

private:
  MemPointerDecomposedForm(Node* pointer, const GrowableArray<MemPointerSummand>& summands, const NoOverflowInt con)
    :_pointer(pointer), _con(con) {
    assert(!_con.is_NaN(), "non-NaN constant");
    assert(summands.length() <= SUMMANDS_SIZE, "summands must fit");
    for (int i = 0; i < summands.length(); i++) {
      MemPointerSummand s = summands.at(i);
      assert(s.variable() != nullptr, "variable cannot be null");
      assert(!s.scale().is_NaN(), "non-NaN scale");
      _summands[i] = s;
    }
  }

public:
  static MemPointerDecomposedForm make(Node* pointer, const GrowableArray<MemPointerSummand>& summands, const NoOverflowInt con) {
    if (summands.length() <= SUMMANDS_SIZE) {
      return MemPointerDecomposedForm(pointer, summands, con);
    } else {
      return MemPointerDecomposedForm(pointer);
    }
  }

  MemPointerAliasing get_aliasing_with(const MemPointerDecomposedForm& other
                                       NOT_PRODUCT( COMMA const TraceMemPointer& trace) ) const;

  const MemPointerSummand summands_at(const uint i) const {
    assert(i < SUMMANDS_SIZE, "in bounds");
    return _summands[i];
  }

  const NoOverflowInt con() const { return _con; }

#ifndef PRODUCT
  void print_on(outputStream* st) const {
    if (_pointer == nullptr) {
      st->print_cr("MemPointerDecomposedForm empty.");
      return;
    }
    st->print("MemPointerDecomposedForm[%d %s:  con = ", _pointer->_idx, _pointer->Name());
    _con.print_on(st);
    for (int i = 0; i < SUMMANDS_SIZE; i++) {
      const MemPointerSummand& summand = _summands[i];
      if (summand.variable() != nullptr) {
        st->print(", ");
        summand.print_on(st);
      }
    }
    st->print_cr("]");
  }
#endif
};

class MemPointerDecomposedFormParser : public StackObj {
private:
  const MemNode* _mem;

  // Internal data-structures for parsing.
  NoOverflowInt _con;
  GrowableArray<MemPointerSummand> _worklist;
  GrowableArray<MemPointerSummand> _summands;

  // Resulting decomposed-form.
  MemPointerDecomposedForm _decomposed_form;

public:
  MemPointerDecomposedFormParser(const MemNode* mem) : _mem(mem), _con(NoOverflowInt(0)) {
    _decomposed_form = parse_decomposed_form();
  }

  const MemPointerDecomposedForm decomposed_form() const { return _decomposed_form; }

private:
  MemPointerDecomposedForm parse_decomposed_form();
  void parse_sub_expression(const MemPointerSummand summand);

  bool is_safe_to_decompose_op(const int opc, const NoOverflowInt scale) const;
};

// Facility to parse the pointer of a Load or Store, so that aliasing between two such
// memory operations can be determined (e.g. adjacency).
class MemPointer : public StackObj {
private:
  const MemNode* _mem;
  const MemPointerDecomposedForm _decomposed_form;

  NOT_PRODUCT( const TraceMemPointer& _trace; )

public:
  MemPointer(const MemNode* mem NOT_PRODUCT( COMMA const TraceMemPointer& trace)) :
    _mem(mem),
    _decomposed_form(init_decomposed_form(_mem))
    NOT_PRODUCT( COMMA _trace(trace) )
  {
#ifndef PRODUCT
    if (_trace.is_trace_pointer()) {
      tty->print_cr("MemPointer::MemPointer:");
      tty->print("mem: "); mem->dump();
      _mem->in(MemNode::Address)->dump_bfs(5, 0, "d");
      _decomposed_form.print_on(tty);
    }
#endif
  }

  const MemNode* mem() const { return _mem; }
  const MemPointerDecomposedForm decomposed_form() const { return _decomposed_form; }
  bool is_adjacent_to_and_before(const MemPointer& other) const;

private:
  static const MemPointerDecomposedForm init_decomposed_form(const MemNode* mem) {
    assert(mem->is_Store(), "only stores are supported");
    ResourceMark rm;
    MemPointerDecomposedFormParser parser(mem);
    return parser.decomposed_form();
  }
};

#endif // SHARE_OPTO_MEMPOINTER_HPP
