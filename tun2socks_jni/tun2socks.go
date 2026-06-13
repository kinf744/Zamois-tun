package tun2socks_jni

import (
    "fmt"
    "github.com/xjasonlyu/tun2socks/v2/engine"
)

var started bool

func StartTun2Socks(tunFd int, socksAddr string, mtu int) error {
    if started {
        engine.Stop()
    }
    key := &engine.Key{
        Device: fmt.Sprintf("fd://%d", tunFd),
        Proxy:  socksAddr,
        MTU:    mtu,
    }
    engine.Insert(key)
    engine.Start()
    started = true
    return nil
}

func StopTun2Socks() {
    engine.Stop()
    started = false
}
