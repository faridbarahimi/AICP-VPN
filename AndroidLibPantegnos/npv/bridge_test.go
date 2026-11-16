package npv

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestInspectRejectsUnsupportedExtension(t *testing.T) {
	var got map[string]any
	if err := json.Unmarshal([]byte(Inspect("profile.ovpn", []byte("client"))), &got); err != nil {
		t.Fatal(err)
	}
	if got["ok"] != false || !strings.Contains(got["error"].(string), ".npvt") {
		t.Fatalf("unexpected inspect result: %#v", got)
	}
}

func TestDecryptRejectsOversizedInput(t *testing.T) {
	data := make([]byte, maxInputBytes+1)
	var got map[string]any
	if err := json.Unmarshal([]byte(Decrypt("profile.npvt", data, "")), &got); err != nil {
		t.Fatal(err)
	}
	if got["ok"] != false || !strings.Contains(got["error"].(string), "4 MiB") {
		t.Fatalf("unexpected decrypt result: %#v", got)
	}
}

func TestInspectMalformedNPVSReturnsJSON(t *testing.T) {
	var got map[string]any
	if err := json.Unmarshal([]byte(Inspect("broken.npvs", []byte{0xff, 0x00})), &got); err != nil {
		t.Fatal(err)
	}
	if got["ok"] != true && got["error"] == nil {
		t.Fatalf("expected a structured inspection result: %#v", got)
	}
}

func TestDecryptRejectsOversizedPassphrase(t *testing.T) {
	var got map[string]any
	if err := json.Unmarshal([]byte(Decrypt("profile.npvs", []byte("NPVS"), strings.Repeat("x", 513))), &got); err != nil {
		t.Fatal(err)
	}
	if got["ok"] != false || !strings.Contains(got["error"].(string), "512-byte") {
		t.Fatalf("unexpected decrypt result: %#v", got)
	}
}
