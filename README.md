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

