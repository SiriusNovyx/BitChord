package hub

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

func socketPair(t *testing.T) (*SafeConn, *websocket.Conn) {
	t.Helper()
	ready := make(chan *SafeConn, 1)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, err := (&websocket.Upgrader{}).Upgrade(w, r, nil)
		if err != nil {
			return
		}
		ready <- NewSafeConn(conn)
	}))
	t.Cleanup(server.Close)
	client, _, err := websocket.DefaultDialer.Dial("ws"+strings.TrimPrefix(server.URL, "http"), nil)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = client.Close() })
	sender := <-ready
	t.Cleanup(func() { _ = sender.Close() })
	return sender, client
}

func TestSlowSocketDoesNotBlockStateProducer(t *testing.T) {
	sender, _ := socketPair(t)
	started := time.Now()
	_ = sender.WriteJSON(strings.Repeat("x", 16*1024*1024))
	if elapsed := time.Since(started); elapsed > 2*time.Second {
		t.Fatalf("state producer blocked on socket I/O for %s", elapsed)
	}
}

func TestQueuedFramesKeepOrderAndSnapshot(t *testing.T) {
	sender, client := socketPair(t)
	frame := map[string]int{"seq": 1}
	if err := sender.WriteJSON(frame); err != nil {
		t.Fatal(err)
	}
	frame["seq"] = 2
	if err := sender.WriteJSON(frame); err != nil {
		t.Fatal(err)
	}
	_ = client.SetReadDeadline(time.Now().Add(2 * time.Second))
	for _, expected := range []int{1, 2} {
		var got map[string]int
		if err := client.ReadJSON(&got); err != nil {
			t.Fatal(err)
		}
		if got["seq"] != expected {
			t.Fatalf("want %d, got %v", expected, got)
		}
	}
}

func TestByeIsDeliveredBeforeGracefulClose(t *testing.T) {
	sender, client := socketPair(t)
	if err := sender.WriteJSON(map[string]string{"type": "bye"}); err != nil {
		t.Fatal(err)
	}
	sender.CloseAfterDrain()
	_ = client.SetReadDeadline(time.Now().Add(2 * time.Second))
	var frame map[string]string
	if err := client.ReadJSON(&frame); err != nil {
		t.Fatal(err)
	}
	if frame["type"] != "bye" {
		t.Fatalf("got %v", frame)
	}
	if err := client.ReadJSON(&frame); err == nil {
		t.Fatal("expected socket closure after bye")
	}
}
