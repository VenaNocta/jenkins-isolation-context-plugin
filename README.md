# NOTE: Rework & Migration in Progress

See details at: [jenkins-artifact-lifecycle-project](https://github.com/VenaNocta/jenkins-artifact-lifecycle-project)

---

# Isolation Context

## Basic - Linux Isolation Context

### Setup

```bash
cat /etc/sudoers.d/jenkins-isolation-context 
%ci_wheel ALL=(root) NOPASSWD: \
  /usr/bin/bash *, \
  /usr/bin/python3 *, \
  /usr/bin/kill -KILL *, \
  /usr/bin/nsenter --target=* --mount --uts --ipc --net --pid -- *, \
  /usr/bin/nsenter --target=* --net -- *, \
  /usr/sbin/ip link add *, \
  /usr/sbin/ip link set * netns *, \
  /usr/sbin/ip link del *, \
  /usr/sbin/ip addr add *, \
  /usr/sbin/ip link set * up

# EL9 doesn't support it with --wdns'
#  /usr/bin/nsenter --target=* --mount --uts --ipc --net --pid --wdns=* -- *, \
```

### Known Issues

+ **[FIXED]** if a run is currently blocked (no executors available) and starts later the correct isolationCtx does not get injected

# Error Codes

## `[E:LNX:XXXX]` - Linux Isolation Error Codes

### `[E:LNX:1:XXX]` - IsolationContext: Linux Namespace Context (`LinuxNamespaceContext`)

> NOTE: This section also includes the errors related to the context's specific functions, provided by following classes:
> - `LnxNamespaceLauncherDecorator`

#### `[E:LNX:1:100]` - Unexpected Error on Setup

**Details**

The `LinuxNamespaceContext` tried to setup the isolated environment but failed. You are adviced to look up the error log!


#### `[E:LNX:1:101]` - Active Handler does not support basic Linux OS operations

**Details**

The `LinuxNamespaceContext` tried to setup the isolated environment but failed as the Handler currently active on the Node, on which the `IsolationContext` is active, is not assignable to `LnxHostHandler` and thereby does not support basic Linux OS operations.


#### `[E:LNX:1:102]` - Active Handler does not support OCI-Runtime

**Details**

The `LinuxNamespaceContext` tried to setup the isolated environment but failed as the Handler currently active on the Node, on which the `IsolationContext` is active, is not assignable to `OciRuntimeHandler` and thereby does not support the management OCI-Runtimes.


#### `[E:LNX:1:103]` - Active Handler does not support Network-Runtime

**Details**

The `LinuxNamespaceContext` tried to setup the isolated environment but failed as the Handler currently active on the Node, on which the `IsolationContext` is active, is not assignable to `LnxNetNamespaceHandler` and thereby does not provide support the required Linux net-namespace management capabilities.


#### `[E:LNX:1:104]` - Unexpected Error on Network Setup

**Details**

The `LinuxNamespaceContext` tried to setup the network for the isolated environment but failed. You are adviced to look up the error log!


#### `[E:LNX:1:200]` - Unexpected Error on Isolation Environment tear down

**Details**

The `LinuxNamespaceContext` tried to tear down the isolated environment but failed. You are adviced to look up the error log!


#### `[E:LNX:1:202]` - Active Handler does not support OCI-Runtime

**Details**

The `LinuxNamespaceContext` tried to tear down the isolated environment but failed as the Handler currently active on the Node, on which the `IsolationContext` is active, is not assignable to `OciRuntimeHandler` and thereby does not support the management OCI-Runtimes.


#### `[E:LNX:1:300]` - Unexpected Error upon launching cmd in the isolated environment

**Details**

The `LnxNamespaceLauncherDecorator` tried to launch a cmd in the isolated environment, but failed unexpectedly. You are adviced to look up the error log!


#### `[E:LNX:1:301]` - Namespace unavailable

**Details**

The `LnxNamespaceLauncherDecorator` failed to acquire the process id of the isolated environment, suggesting it's setup failed or never ran.


#### `[E:LNX:1:302]` - Identity unavailable

**Details**

The `LnxNamespaceLauncherDecorator` tried to launch a cmd in the isolated environment, but the required `LnxIdentity` could not be retrieved from the the Handler currently active on the Node, on which the `IsolationContext` is active.


#### `[E:LNX:1:400]` - Unexpected Error when connecting host-only veth-link to isolated environment

**Details**

The `LinuxNamespaceContext` tried to connect a new veth-pair to the host and the isolated environment but failed. You are adviced to look up the error log!


#### `[E:LNX:1:401]` - Failed to connect host-only veth-link to isolated environment

**Details**

The `LinuxNamespaceContext` tried to connect a new veth-pair to the host and the isolated environment but failed, as the Handler currently active on the Node, on which the `IsolationContext` is active, is not supported by the `LinuxNamespaceContext` or failed to handle the task.





