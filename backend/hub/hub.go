package hub

import (
	"encoding/json"
	"errors"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gorilla/websocket"
)

// SafeConn serializes immutable frames through a bounded writer queue.
type SafeConn struct {
	conn        *websocket.Conn
	frames      chan []byte
	done        chan struct{}
	closeOnce   sync.Once
	queuedBytes atomic.Int64
	closing     atomic.Bool
}

func NewSafeConn(conn *websocket.Conn) *SafeConn {
	s := &SafeConn{conn: conn, frames: make(chan []byte, 32), done: make(chan struct{})}
	go s.writeLoop()
	return s
}

func (s *SafeConn) WriteJSON(v interface{}) error {
	if s.closing.Load() {
		return errors.New("websocket is closing")
	}
	frame, err := json.Marshal(v)
	if err != nil {
		return err
	}
	const maxQueuedBytes = 1024 * 1024
	if s.queuedBytes.Add(int64(len(frame))) > maxQueuedBytes {
		s.queuedBytes.Add(-int64(len(frame)))
		_ = s.Close()
		return errors.New("websocket outbound queue exceeded its byte limit")
	}
	select {
	case <-s.done:
		s.queuedBytes.Add(-int64(len(frame)))
		return errors.New("websocket is closed")
	case s.frames <- frame:
		return nil
	default:
		s.queuedBytes.Add(-int64(len(frame)))
		_ = s.Close()
		return errors.New("websocket outbound queue is full")
	}
}

func (s *SafeConn) writeLoop() {
	defer s.Close()
	for {
		select {
		case <-s.done:
			return
		case frame := <-s.frames:
			if frame == nil {
				return
			}
			_ = s.conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
			err := s.conn.WriteMessage(websocket.TextMessage, frame)
			s.queuedBytes.Add(-int64(len(frame)))
			if err != nil {
				return
			}
		}
	}
}

func (s *SafeConn) Close() error {
	// Gorilla permits Close concurrently with all other connection methods.
	var err error
	s.closing.Store(true)
	s.closeOnce.Do(func() { close(s.done); err = s.conn.Close() })
	return err
}

// CloseAfterDrain lets an already-enqueued bye frame reach the peer first.
func (s *SafeConn) CloseAfterDrain() {
	if !s.closing.CompareAndSwap(false, true) {
		return
	}
	select {
	case <-s.done:
	case s.frames <- nil:
	default:
		_ = s.Close()
	}
}

// Hub coordinates active WebSocket connections across rooms.
type Hub struct {
	mu      sync.RWMutex
	sockets map[string]map[string]*SafeConn // code -> memberId -> SafeConn
}

func NewHub() *Hub {
	return &Hub{
		sockets: make(map[string]map[string]*SafeConn),
	}
}

func (h *Hub) Attach(code, memberId string, conn *websocket.Conn) *SafeConn {
	sc := NewSafeConn(conn)
	h.mu.Lock()
	room, ok := h.sockets[code]
	if !ok {
		room = make(map[string]*SafeConn)
		h.sockets[code] = room
	}
	prev := room[memberId]
	room[memberId] = sc
	h.mu.Unlock()

	// Close previous connection outside lock so it doesn't stall other parties
	if prev != nil && prev != sc {
		_ = prev.Close()
	}
	return sc
}

func (h *Hub) Detach(code, memberId string, sc *SafeConn) bool {
	h.mu.Lock()
	defer h.mu.Unlock()

	room, ok := h.sockets[code]
	if !ok {
		return false
	}
	if room[memberId] != sc {
		return false
	}
	delete(room, memberId)
	if len(room) == 0 {
		delete(h.sockets, code)
	}
	return true
}

func (h *Hub) DropParty(code string) {
	h.mu.Lock()
	room := h.sockets[code]
	delete(h.sockets, code)
	h.mu.Unlock()

	if room != nil {
		for _, sc := range room {
			_ = sc.Close()
		}
	}
}

func (h *Hub) MembersOnline(code string) []string {
	h.mu.RLock()
	defer h.mu.RUnlock()

	room := h.sockets[code]
	if room == nil {
		return nil
	}
	members := make([]string, 0, len(room))
	for mid := range room {
		members = append(members, mid)
	}
	return members
}

func (h *Hub) ActiveCodes() []string {
	h.mu.RLock()
	defer h.mu.RUnlock()

	codes := make([]string, 0, len(h.sockets))
	for c := range h.sockets {
		codes = append(codes, c)
	}
	return codes
}

func (h *Hub) Send(code, memberId string, payload interface{}) {
	h.mu.RLock()
	room := h.sockets[code]
	var target *SafeConn
	if room != nil {
		target = room[memberId]
	}
	h.mu.RUnlock()

	if target != nil {
		_ = target.WriteJSON(payload)
	}
}

func (h *Hub) CloseMember(code, memberId string) {
	h.mu.RLock()
	room := h.sockets[code]
	var target *SafeConn
	if room != nil {
		target = room[memberId]
	}
	h.mu.RUnlock()
	if target != nil {
		target.CloseAfterDrain()
	}
}

func (h *Hub) Broadcast(code string, payload interface{}, skipMemberId string) {
	h.mu.RLock()
	room := h.sockets[code]
	if room == nil || len(room) == 0 {
		h.mu.RUnlock()
		return
	}
	targets := make([]*SafeConn, 0, len(room))
	for mid, sc := range room {
		if mid != skipMemberId {
			targets = append(targets, sc)
		}
	}
	h.mu.RUnlock()

	// Concurrently write to all targets so one slow mobile network does not delay others
	var wg sync.WaitGroup
	for _, sc := range targets {
		wg.Add(1)
		go func(conn *SafeConn) {
			defer wg.Done()
			_ = conn.WriteJSON(payload)
		}(sc)
	}
	wg.Wait()
}
