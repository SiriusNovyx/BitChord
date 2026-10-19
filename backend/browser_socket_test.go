package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/KabirSinghBhatia/BitChord/backend/config"
	"github.com/gorilla/websocket"
)

func TestBrowserSocketAuthenticationBoundary(t *testing.T) {
	oldOrigins := config.AllowedOrigins
	config.AllowedOrigins = []string{"https://player.example"}
	t.Cleanup(func() { config.AllowedOrigins = oldOrigins })
	ts := setupTestServer()
	defer ts.Close()
	res, err := http.Post(ts.URL+"/api/parties", "application/json",
		bytes.NewBufferString(`{"userId":"browser-test","deviceId":"test","displayName":"Test"}`))
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusCreated {
		t.Fatalf("create status: %d", res.StatusCode)
	}
	var created struct {
		Code  string `json:"code"`
		Token string `json:"token"`
	}
	if err := json.NewDecoder(res.Body).Decode(&created); err != nil {
		t.Fatal(err)
	}
	address := "ws" + strings.TrimPrefix(ts.URL, "http") + fmt.Sprintf("/ws/parties/%s", created.Code)
	for _, tc := range []struct {
		name      string
		origin    string
		bearer    string
		protocols []string
		query     string
		status    int
		answer    string
	}{
		{"allowed browser", "https://player.example", "", []string{"bitchord.v1", "bitchord.token." + created.Token}, "", http.StatusSwitchingProtocols, "bitchord.v1"},
		{"native bearer", "", created.Token, nil, "", http.StatusSwitchingProtocols, ""},
		{"wrong token", "https://player.example", "", []string{"bitchord.v1", "bitchord.token.invalid"}, "", http.StatusUnauthorized, ""},
		{"disallowed origin", "https://other.example", "", []string{"bitchord.v1", "bitchord.token." + created.Token}, "", http.StatusForbidden, ""},
		{"URL token rejected", "https://player.example", "", []string{"bitchord.v1"}, "?token=" + created.Token, http.StatusUnauthorized, ""},
		{"bearer takes precedence", "https://player.example", "invalid", []string{"bitchord.v1", "bitchord.token." + created.Token}, "", http.StatusUnauthorized, ""},
	} {
		t.Run(tc.name, func(t *testing.T) {
			headers := http.Header{}
			if tc.origin != "" {
				headers.Set("Origin", tc.origin)
			}
			if tc.bearer != "" {
				headers.Set("Authorization", "Bearer "+tc.bearer)
			}
			dialer := websocket.Dialer{Subprotocols: tc.protocols}
			conn, response, err := dialer.Dial(address+tc.query, headers)
			if response == nil {
				t.Fatalf("missing handshake response: %v", err)
			}
			defer response.Body.Close()
			if response.StatusCode != tc.status {
				t.Fatalf("handshake status = %d, want %d", response.StatusCode, tc.status)
			}
			if conn == nil {
				if err == nil {
					t.Fatal("rejected handshake returned no error")
				}
				return
			}
			defer conn.Close()
			if conn.Subprotocol() != tc.answer {
				t.Fatal("unexpected selected protocol")
			}
			var welcome struct {
				Type string `json:"type"`
			}
			_ = conn.SetReadDeadline(time.Now().Add(2 * time.Second))
			if err := conn.ReadJSON(&welcome); err != nil || welcome.Type != "welcome" {
				t.Fatalf("missing welcome: %v", err)
			}
		})
	}
}
