#!/bin/bash
# Performance comparison between Java and C++ implementations

echo "========================================="
echo "RandomX Performance Comparison"
echo "========================================="
echo ""

# Test parameters
NONCES=1000
INIT_THREADS=4

echo "Test Configuration:"
echo "  - Nonces: $NONCES"
echo "  - Init Threads: $INIT_THREADS"
echo "  - Mode: Mining (full memory, 2080 MiB)"
echo "  - JIT: Enabled with SECURE flag"
echo "  - AES: Software (for compatibility)"
echo ""

echo "========================================="
echo "Java Implementation (via JNA)"
echo "========================================="
./run-benchmark.sh --mine --jit --secure --softAes --nonces $NONCES --init $INIT_THREADS 2>&1 | grep -vE "DEBUG|INFO \[|WARNING"

echo ""
echo "========================================="
echo "C++ Implementation (native)"
echo "========================================="
echo "Note: C++ benchmark executable not found in this repository"
echo "Build and run RandomX/src/tests/benchmark on the same host for a valid comparison."
echo ""

echo "========================================="
echo "Summary"
echo "========================================="
echo "No Java/C++ percentage is reported without measurements from the same CPU,"
echo "flags, worker count, affinity, dataset and nonce range."
