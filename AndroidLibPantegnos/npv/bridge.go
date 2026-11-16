// Package npv exposes the MIT-licensed Pantegnos NPV decryptors to Android.
package npv

import (
	"encoding/json"
	"fmt"
	"path/filepath"
	"strings"

	_ "aicpnpv/impl"
	"aicpnpv/modules"
)

const maxInputBytes = 4 << 20

type result struct {
	OK            bool   `json:"ok"`
	Supported     bool   `json:"supported,omitempty"`
	NeedsPassword bool   `json:"needsPassword,omitempty"`
	Module        string `json:"module,omitempty"`
	Text          string `json:"text,omitempty"`
	Error         string `json:"error,omitempty"`
}

// Inspect returns whether an NPVT/NPVS file is recognized and whether it needs a passphrase.
func Inspect(fileName string, data []byte) (out string) {
	defer func() {
		if v := recover(); v != nil {
			out = encode(result{Error: fmt.Sprintf("profile inspection failed safely (%T)", v)})
		}
	}()
	if len(data) == 0 || len(data) > maxInputBytes {
		return encode(result{Error: "file is empty or exceeds the 4 MiB safety limit"})
	}
	ext := strings.ToLower(filepath.Ext(filepath.Base(fileName)))
	if ext != ".npvt" && ext != ".npvs" {
		return encode(result{Error: "only .npvt and .npvs files are supported"})
	}
	mod, proto, payload := modules.Lookup(fileName, data)
	if mod == nil {
		return encode(result{Error: "unrecognized NPVT/NPVS container"})
	}
	needs := mod.NeedsPassword != nil && mod.NeedsPassword(proto, payload)
	return encode(result{OK: true, Supported: true, NeedsPassword: needs, Module: mod.Name})
}

// Decrypt decrypts a local NPVT/NPVS file. The returned JSON contains recovered text or a safe error.
func Decrypt(fileName string, data []byte, password string) (out string) {
	defer func() {
		if v := recover(); v != nil {
			out = encode(result{Error: fmt.Sprintf("decryption failed safely (%T)", v)})
		}
	}()
	if len(data) == 0 || len(data) > maxInputBytes {
		return encode(result{Error: "file is empty or exceeds the 4 MiB safety limit"})
	}
	if len(password) > 512 {
		return encode(result{Error: "profile passphrase exceeds the 512-byte safety limit"})
	}
	ext := strings.ToLower(filepath.Ext(filepath.Base(fileName)))
	if ext != ".npvt" && ext != ".npvs" {
		return encode(result{Error: "only .npvt and .npvs files are supported"})
	}
	mod, proto, payload := modules.Lookup(fileName, data)
	if mod == nil {
		return encode(result{Error: "unrecognized NPVT/NPVS container"})
	}
	decrypted, err := mod.Decrypt(modules.Request{FileName: filepath.Base(fileName), Data: data, Proto: proto, Payload: payload, Password: password})
	if err != nil {
		return encode(result{Error: err.Error(), Module: mod.Name})
	}
	if strings.TrimSpace(decrypted.Text) == "" {
		return encode(result{Error: "decryption produced no importable profile", Module: mod.Name})
	}
	return encode(result{OK: true, Supported: true, Module: mod.Name, Text: decrypted.Text})
}

func encode(v result) string {
	b, err := json.Marshal(v)
	if err != nil {
		return `{"ok":false,"error":"result encoding failed"}`
	}
	return string(b)
}
