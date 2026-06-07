// porcupine-check: the TRUSTED third-party linearizability checker for the A3
// harness (ADR-0032). It reads a checker-neutral op-history (written by the Java
// orchestrator), partitions it per key, and checks each key as an independent
// linearizable register using anishathalye/porcupine — the checker etcd uses.
//
// This program is deliberately tiny and dumb: all the policy (which ops to
// include, how to model ack != commit, how to map timeouts) lives in the Java
// recorder and is pinned by the checker self-test suite (a3-harness-design.md
// §11.3). This binary only runs the trusted algorithm.
//
// History JSON schema (one object, written by io.configd.linz.history.HistoryWriter):
//
//	{"ops":[
//	  {"client":0,"key":"k","type":"put","value":"<token>","call":123,"ret":999},
//	  {"client":1,"key":"k","type":"read","value":"<observed token or empty>","call":200,"ret":250},
//	  {"client":0,"key":"k","type":"delete","value":"","call":300,"ret":9999}
//	]}
//
// Semantics (the Java side has already applied these — see §6/§7 of the design):
//   - put/delete are modeled as writes; their Output is ignored by the register
//     model, so an indeterminate (ack != commit) write is encoded by stretching
//     its "ret" to END (max ts + 1) on the Java side: it may linearize anywhere
//     at or after its call. Floating writes are always legal -> they can never
//     cause a false RED, only the unique-token + confirming-read structure makes
//     a real violation un-linearizable.
//   - read carries an observed token (or "" for absent/deleted). These OK reads
//     are the real-time backbone.
//   - indeterminate reads (lin-read 503) and definite-fail ops are already
//     omitted by the Java recorder; this binary never sees them.
//
// Exit codes: 0 = LINEARIZABLE, 1 = NON-LINEARIZABLE, 2 = INDETERMINATE/usage/IO.
package main

import (
	"encoding/json"
	"fmt"
	"os"
	"sort"
	"time"

	"github.com/anishathalye/porcupine"
)

type opJSON struct {
	Client int    `json:"client"`
	Key    string `json:"key"`
	Type   string `json:"type"` // put | read | delete
	Value  string `json:"value"`
	Call   int64  `json:"call"`
	Ret    int64  `json:"ret"`
}

type historyJSON struct {
	Ops []opJSON `json:"ops"`
}

// register input: a write carries its value; a read carries nothing (the
// observed value is the Operation's Output).
type regInput struct {
	isRead bool
	value  string
}

// A per-key linearizable register. Empty register == "" (also the value a
// DELETE writes, and what an absent-key read observes).
var registerModel = porcupine.Model{
	Init: func() interface{} { return "" },
	Step: func(state, input, output interface{}) (bool, interface{}) {
		in := input.(regInput)
		st := state.(string)
		if in.isRead {
			return output.(string) == st, st // read is legal iff it observed the current value
		}
		return true, in.value // a write (put value / delete "") is always legal
	},
	Equal: func(a, b interface{}) bool { return a.(string) == b.(string) },
	DescribeOperation: func(input, output interface{}) string {
		in := input.(regInput)
		if in.isRead {
			return fmt.Sprintf("read() -> %q", output.(string))
		}
		return fmt.Sprintf("write(%q)", in.value)
	},
}

// dumpKey prints a key's operations (sorted by call) to aid harness debugging.
func dumpKey(key string, ops []porcupine.Operation) {
	sorted := make([]porcupine.Operation, len(ops))
	copy(sorted, ops)
	sort.Slice(sorted, func(i, j int) bool { return sorted[i].Call < sorted[j].Call })
	fmt.Printf("  --- ops for illegal key %q (sorted by call) ---\n", key)
	for _, o := range sorted {
		in := o.Input.(regInput)
		kind := "write"
		if in.isRead {
			kind = "read "
		}
		v := in.value
		if in.isRead {
			v = o.Output.(string)
		}
		fmt.Printf("  c%-2d %s val=%-18q call=%-12d ret=%d\n", o.ClientId, kind, v, o.Call, o.Return)
	}
}

func main() {
	if len(os.Args) != 2 {
		fmt.Fprintln(os.Stderr, "usage: porcupine-check <history.json>")
		os.Exit(2)
	}
	raw, err := os.ReadFile(os.Args[1])
	if err != nil {
		fmt.Fprintf(os.Stderr, "read %s: %v\n", os.Args[1], err)
		os.Exit(2)
	}
	var hist historyJSON
	if err := json.Unmarshal(raw, &hist); err != nil {
		fmt.Fprintf(os.Stderr, "parse %s: %v\n", os.Args[1], err)
		os.Exit(2)
	}

	// Partition by key — each key is an independent linearizable register
	// (the standard sound `independent` reduction; design §3/§10).
	byKey := map[string][]porcupine.Operation{}
	keys := []string{}
	for _, o := range hist.Ops {
		isRead := o.Type == "read"
		op := porcupine.Operation{
			ClientId: o.Client,
			Input:    regInput{isRead: isRead, value: o.Value},
			Call:     o.Call,
			Output:   o.Value, // only read uses Output; ignored for writes
			Return:   o.Ret,
		}
		if _, seen := byKey[o.Key]; !seen {
			keys = append(keys, o.Key)
		}
		byKey[o.Key] = append(byKey[o.Key], op)
	}
	sort.Strings(keys)

	// Per-key check. A run-wide checker timeout bounds Porcupine's superlinear
	// worst case; a timed-out key is INDETERMINATE, never a silent pass.
	const perKeyTimeout = 60 * time.Second
	overall := porcupine.Ok
	anyKeys := false
	dump := os.Getenv("PORCUPINE_DUMP") != ""
	for _, k := range keys {
		anyKeys = true
		res := porcupine.CheckOperationsTimeout(registerModel, byKey[k], perKeyTimeout)
		fmt.Printf("key %-24q ops=%-5d -> %s\n", k, len(byKey[k]), res)
		switch res {
		case porcupine.Illegal:
			overall = porcupine.Illegal
			if dump {
				dumpKey(k, byKey[k])
			}
		case porcupine.Unknown:
			if overall != porcupine.Illegal {
				overall = porcupine.Unknown
			}
		}
	}
	if !anyKeys {
		fmt.Println("(empty history)")
	}

	switch overall {
	case porcupine.Ok:
		fmt.Println("VERDICT: LINEARIZABLE")
		os.Exit(0)
	case porcupine.Illegal:
		fmt.Println("VERDICT: NON-LINEARIZABLE")
		os.Exit(1)
	default:
		fmt.Println("VERDICT: INDETERMINATE (checker timeout)")
		os.Exit(2)
	}
}
