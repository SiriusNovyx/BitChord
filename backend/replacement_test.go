package main

import (
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

func TestReplacementSocketRemainsConnected(t *testing.T) {
	p, err := store.Create()
	if err != nil {
		t.Fatal(err)
	}
	defer store.Drop(p.Code)
	p.Lock()
	member, err := p.Join("replace-user", "replace-device", "Host", nil)
	token := member.Token
	p.Unlock()
	if err != nil {
		t.Fatal(err)
	}
	server := setupTestServer()
	defer server.Close()
	endpoint := "ws" + strings.TrimPrefix(server.URL, "http") + "/ws/parties/" + p.Code
	headers := http.Header{"Authorization": []string{"Bearer " + token}}
	first, _, err := websocket.DefaultDialer.Dial(endpoint, headers)
	if err != nil {
		t.Fatal(err)
	}
	defer first.Close()
	var welcome map[string]interface{}
	if err := first.ReadJSON(&welcome); err != nil {
		t.Fatal(err)
	}
	replacement, _, err := websocket.DefaultDialer.Dial(endpoint, headers)
	if err != nil {
		t.Fatal(err)
	}
	defer replacement.Close()
	_ = replacement.SetReadDeadline(time.Now().Add(2 * time.Second))
	if err := replacement.ReadJSON(&welcome); err != nil {
		t.Fatal(err)
	}
	// Wait until the old handler has emitted its cleanup frame. It must not
	// publish a disconnected member when the replacement is still attached.
	deadline := time.Now().Add(300 * time.Millisecond)
	for time.Now().Before(deadline) {
		p.Lock()
		connected := member.Connected
		p.Unlock()
		if !connected {
			t.Fatal("old handler marked the replacement disconnected")
		}
		time.Sleep(time.Millisecond)
	}
}
