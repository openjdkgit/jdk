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
#ifndef NMT_REGIONSTREE_HPP
#define NMT_REGIONSTREE_HPP

#include "logging/log.hpp"
#include "nmt/nmtCommon.hpp"
#include "nmt/vmatree.hpp"
#include "nmt/vmtCommon.hpp"

class RegionsTree : public VMATree {
 private:
  NativeCallStackStorage _ncs_storage;

 public:
  RegionsTree(bool with_storage) : VMATree() , _ncs_storage(with_storage) { }


  ReservedMemoryRegion find_reserved_region(address addr, bool with_trace = false);

  SummaryDiff commit_region(address addr, size_t size, const NativeCallStack& stack);
  SummaryDiff uncommit_region(address addr, size_t size);

  using Node = VMATree::TreapNode;

  class NodeHelper {
      Node* _node;
      public:
      NodeHelper() : _node(nullptr) { }
      NodeHelper(Node* node) : _node(node) { }
      inline bool is_valid() { return _node != nullptr; }
      inline void clear_node() { _node = nullptr; }
      inline VMATree::position position() { return _node->key(); }
      inline bool is_committed_begin() { return ((uint8_t)out_state() & (uint8_t)VMATree::StateType::Committed) >= 2; }
      inline bool is_released_begin() { return out_state() == VMATree::StateType::Released; }
      inline bool is_reserved_begin() { return ((uint8_t)out_state() & (uint8_t)VMATree::StateType::Reserved) == 1; }
      inline VMATree::StateType in_state() { return _node->val().in.type(); }
      inline VMATree::StateType out_state() { return _node->val().out.type(); }
      inline size_t distance_from(NodeHelper& other) { return position() - other.position(); }
      inline NativeCallStackStorage::StackIndex out_stack_index() { return _node->val().out.stack(); }
      inline MemTag in_flag() { return _node->val().in.mem_tag(); }
      inline MemTag out_flag() { return _node->val().out.mem_tag(); }
      inline void set_in_flag(MemTag flag) { _node->val().in.set_flag(flag); }
      inline void set_out_flag(MemTag flag) { _node->val().out.set_flag(flag); }
      inline void print_on(outputStream* st) {
        auto st_str = [&](int s){
          return s == (int)VMATree::StateType::Released ? "Rl" :
                 s ==  (int)VMATree::StateType::Reserved ? "Rv" : "Cm";
        };
        st->print_cr("pos: " INTPTR_FORMAT " "
                     "%s, %s <|> %s, %s",
                     p2i((address)position()),
                     st_str((int)in_state()),
                     NMTUtil::tag_to_name(in_flag()),
                     st_str((int)out_state()),
                     NMTUtil::tag_to_name(out_flag())
                     );
      }
    };

  void print_on(outputStream* st) {
    visit_in_order([&](Node* node) {
      NodeHelper curr(node);
      curr.print_on(st);
      return true;
    });
  }

  template<typename F> // F == bool(*)(CommittedMemoryRegion&)
  void visit_committed_regions(const ReservedMemoryRegion& rgn, F func) {
    size_t start = (size_t)rgn.base();
    size_t end = (size_t)rgn.end();
    size_t comm_size = 0;
    size_t base = start;

    NodeHelper prev;
    visit_range_in_order(start, end, [&](Node* node) {
      NodeHelper curr(node);
      if (prev.is_valid()) {
        base = prev.position();
        if (prev.is_committed_begin()) {
          comm_size += curr.distance_from(prev);
          if (!curr.is_committed_begin()) {
            auto st = stack(curr);
            CommittedMemoryRegion cmr((address)base, comm_size, st);
            comm_size = 0;
            prev.clear_node();
            if (!func(cmr))
              return false;
          }
        }
      }
      prev = curr;
      return true;
    });
  }

  template<typename F> // F == bool(*)(ReservedMemoryRegion&)
  void visit_reserved_regions(F func) {
    NodeHelper begin_node, prev;
    size_t rgn_size = 0;
    size_t comm_size = 0;
    size_t base = 0;
    MemTag flag = mtNone;
    visit_in_order([&](Node* node) {
      NodeHelper curr(node);
      if (prev.is_valid()) {
        rgn_size += curr.distance_from(prev);
      } else {
        begin_node = curr;
        rgn_size = 0;
      }
      prev = curr;
      if (curr.is_released_begin() || begin_node.out_flag() != curr.out_flag()) {
        auto st = stack(curr);
        size_t r_size = curr.distance_from(begin_node);
        if (r_size != rgn_size) {
          log_debug(nmt)("----------------- size differ, distance: " SIZE_FORMAT " size: " SIZE_FORMAT, r_size, rgn_size);
        }
        if (rgn_size == 0) {
          prev.clear_node();
          return true;
        }
        ReservedMemoryRegion rmr((address)begin_node.position(), rgn_size, st, begin_node.out_flag());
        if (!func(rmr))
          return false;
        rgn_size = 0;
        if (!curr.is_released_begin())
          begin_node = curr;
        else {
          begin_node.clear_node();
          prev.clear_node();
        }
      }

      return true;
    });
  }

  inline RegionData make_region_data(const NativeCallStack& ncs, MemTag flag) {
    return RegionData(_ncs_storage.push(ncs), flag);
  }

  inline const NativeCallStack stack(NodeHelper& node) {
    NativeCallStackStorage::StackIndex si = node.out_stack_index();
    if (!NativeCallStackStorage::is_invalid(si)) {
      return _ncs_storage.get(si);
    }
    return NativeCallStack::empty_stack();
  }
};

#endif // NMT_REGIONSTREE_HPP