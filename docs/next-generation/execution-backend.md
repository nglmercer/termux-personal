# Optional isolated execution backend: status

**Result: not supported in this build.** The backend abstraction and the capability detection exist. No virtual machine is implemented, and the capability is reported as unsupported with the measured reasons. Native Termux execution is the default and is unaffected.

## What exists

* `ExecutionBackend`: `capabilities`, `prepare`, `start`, `stop`, `status`, `logs`, `terminal`, `resources`, `ports`, `cleanup`. An operation a backend cannot perform answers `UNSUPPORTED` with a reason instead of throwing.
* `NativeExecutionBackend` (`native`, the default): describes native execution. It launches nothing itself, because the job coordinator is the one launcher.
* `IsolatedVmBackend` (`isolated-vm`): refuses every operation with the detected reason, starts no process, opens no socket and writes no file.
* `VirtualizationCapabilityDetector`: decides from measured facts, never from the Android version alone. Unknown facts count against support. `resource.capabilities` reports `virtualMachine` from this check.
* `ExecutionBackends`: native is the default; any other backend is chosen explicitly, an unknown ID is not replaced by the default, and nothing ever switches a workspace from native to a VM silently.

## Feasibility review

None of the routes below was run on a device. These are the findings from the platform's documented behavior and from this repository, and each needs confirmation before it is relied on.

| Route | Finding |
|---|---|
| Android Virtualization Framework | The virtual machine API is a platform API guarded by a signature-level permission (`MANAGE_VIRTUAL_MACHINE`) that a normal app does not hold, and the device must declare the `android.software.virtualization_framework` feature. A recent Android version does not imply either. Detected, not assumed. |
| Bundled emulator (for example QEMU) | An app can run a packaged executable only where the platform allows it (this app targets API 28). Without `/dev/kvm` access, which apps normally lack, a guest is fully emulated and slow. Needs packaging per ABI, size, update and integrity handling. |
| Guest image | A guest needs a verified image and 2 GiB or more of storage and RAM, which also competes with the builds the rest of this work protects. |

## Licensing

The main application is GPLv3-only. Podroid publishes GPLv2 source, and QEMU is GPLv2 with some parts under other licenses. Combining GPLv2-only code into a GPLv3-only work is not possible, and redistributing a hypervisor and guest images alongside the app (as separate programs) is a distribution question that needs a legal review. **No Podroid source, asset or binary was imported.** Podroid was used only as an architectural reference for sizing a workload from the device, which `DeviceResourcePolicy` does with its own arithmetic.

## Shipping gates and their state

| Gate | State |
|---|---|
| Technical feasibility verified on a device | not done |
| License review | not done |
| Permissions verified | the platform route needs a permission this app does not hold |
| Runtime assets with integrity checks | no runtime is packaged |
| Native fallback | works; it is the default and is independent of this code |
| Performance overhead measured against native | not measured; no VM exists to measure |
| Reliable shutdown and cleanup | trivially true: nothing is started |

The VM may only be enabled when every gate is met. Until then `IsolatedVmBackend` stays unsupported. No claim is made that a VM would be faster or safer; that needs device measurements of startup time, CPU performance, memory overhead, build time, storage, battery, thermals and stability for native, emulated and AVF execution.
