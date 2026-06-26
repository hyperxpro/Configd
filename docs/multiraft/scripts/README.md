# Workstream C — EC2 measurement scripts (reproducibility)

These provisioned/ran/tore-down the **m6id.4xlarge** on which the re-threaded single-group throughput
ceiling was measured (`../workstream-c-throughput.md`). Adapted from the Phase V scripts
(`docs/netty-migration/scripts/`) for **m6id + instance-store NVMe** (the 'd' = local NVMe; the EBS root
of a plain m6i would distort the fsync-honest path). Same instance *type* as the §7.5 ~800/s baseline.

## Recipe
```bash
export STATE_DIR=/some/scratch/dir          # where the ephemeral key + state env live (NOT the repo)
bash wsc-ec2-provision.sh                    # m6id.4xlarge on-demand, ap-south-1; SSH 22 from your /32 only;
                                             #   ephemeral key; user-data mounts NVMe @ /mnt/nvme, installs Corretto 25 + sysstat + fio
REPO=/path/to/Configd bash wsc-ec2-run.sh    # waits for bootstrap; confirms 16 vCPU + NVMe + fsync baseline;
                                             #   ships the shaded server jar + benchmarks.jar + perf/wsC-ladder.sh;
                                             #   runs the §7.5 rate ladder (production defaults; -Dconfigd.netty.transport=epoll forced)
bash wsc-extra-onbox.sh                      # (on-box) variance + admission axis + thread-level top -H attribution
bash wsc-ec2-teardown.sh                     # terminate + API-verified deletion (instance/EBS/SG/key/.pem) + cost line
```

## Guardrails (charter §2)
- **On-demand, not spot** (a measurement must not be interrupted). Cost ceiling **$5** (run ≈ \$0.50–0.65).
- **Least-privilege:** inbound TCP 22 from the caller's `/32` only (never 0.0.0.0/0); ephemeral key pair; no key in git.
- **Dry-run on a free box first** — `perf/wsC-ladder.sh` validates green before any spend (`WSC_DRYRUN=1`).
- **Verified teardown:** `wsc-ec2-teardown.sh` confirms against the AWS API that the instance is terminated, the
  EBS volume is gone (DeleteOnTermination), the security group is deleted, and the key pair + local `.pem` are removed.

The actual measurement harness is `perf/wsC-ladder.sh` (in the repo root `perf/`), derived from the §7.5
`perf/s75-throughput.sh` so the cluster launch / driver / instrumentation are byte-comparable.
