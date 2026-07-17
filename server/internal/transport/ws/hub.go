package ws

import (
	"context"
	"encoding/json"
	"log/slog"
	"net/http"
	"sync"
	"time"

	"kipotify/internal/domain"
	"kipotify/internal/service"

	"github.com/gorilla/websocket"
)

type Event struct {
	Type    string          `json:"type"`
	Payload json.RawMessage `json:"payload,omitempty"`
}

type Hub struct {
	app      *service.App
	upgrader websocket.Upgrader
	mu       sync.RWMutex
	clients  map[string]map[*Client]bool
}

type Client struct {
	hub    *Hub
	userID string
	conn   *websocket.Conn
	send   chan any
}

func NewHub(app *service.App, allowedOrigins []string) *Hub {
	return &Hub{
		app:     app,
		clients: make(map[string]map[*Client]bool),
		upgrader: websocket.Upgrader{
			CheckOrigin: func(r *http.Request) bool {
				if len(allowedOrigins) == 0 || allowedOrigins[0] == "*" {
					return true
				}
				origin := r.Header.Get("Origin")
				for _, allowed := range allowedOrigins {
					if origin == allowed {
						return true
					}
				}
				return false
			},
		},
	}
}

func (h *Hub) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	token := r.URL.Query().Get("token")
	if token == "" {
		http.Error(w, "missing token", http.StatusUnauthorized)
		return
	}
	userID, err := h.app.UserIDFromToken(token)
	if err != nil {
		http.Error(w, "invalid token", http.StatusUnauthorized)
		return
	}
	conn, err := h.upgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	client := &Client{hub: h, userID: userID, conn: conn, send: make(chan any, 32)}
	h.register(client)
	go client.writePump()
	client.readPump()
}

func (h *Hub) SendTo(userID string, eventType string, payload any) {
	h.mu.RLock()
	defer h.mu.RUnlock()
	for client := range h.clients[userID] {
		client.enqueue(map[string]any{"type": eventType, "payload": payload})
	}
}

func (h *Hub) IsOnline(userID string) bool {
	h.mu.RLock()
	defer h.mu.RUnlock()
	return len(h.clients[userID]) > 0
}

func (h *Hub) register(client *Client) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.clients[client.userID] == nil {
		h.clients[client.userID] = make(map[*Client]bool)
	}
	h.clients[client.userID][client] = true
}

func (h *Hub) unregister(client *Client) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.clients[client.userID] != nil {
		delete(h.clients[client.userID], client)
		if len(h.clients[client.userID]) == 0 {
			delete(h.clients, client.userID)
		}
	}
	close(client.send)
	_ = client.conn.Close()
}

func (c *Client) enqueue(value any) {
	select {
	case c.send <- value:
	default:
		c.hub.unregister(c)
	}
}

func (c *Client) readPump() {
	defer c.hub.unregister(c)
	c.conn.SetReadLimit(64 * 1024)
	_ = c.conn.SetReadDeadline(time.Now().Add(70 * time.Second))
	c.conn.SetPongHandler(func(string) error {
		_ = c.conn.SetReadDeadline(time.Now().Add(70 * time.Second))
		return nil
	})
	for {
		var event Event
		if err := c.conn.ReadJSON(&event); err != nil {
			return
		}
		c.handleEvent(event)
	}
}

func (c *Client) writePump() {
	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case msg, ok := <-c.send:
			if !ok {
				_ = c.conn.WriteMessage(websocket.CloseMessage, nil)
				return
			}
			if err := c.conn.WriteJSON(msg); err != nil {
				return
			}
		case <-ticker.C:
			if err := c.conn.WriteMessage(websocket.PingMessage, nil); err != nil {
				return
			}
		}
	}
}

func (c *Client) handleEvent(event Event) {
	switch event.Type {
	case "message.send":
		var payload struct {
			ToUserID        string  `json:"toUserId"`
			ClientMessageID string  `json:"clientMessageId"`
			Content         string  `json:"content"`
			SharedTrackID   *string `json:"sharedTrackId"`
		}
		if decode(event.Payload, &payload) != nil {
			c.enqueue(errorEvent("invalid_payload", "message.send payload is invalid"))
			return
		}
		msg, err := c.hub.app.SendMessage(context.Background(), c.userID, payload.ToUserID, payload.Content, payload.SharedTrackID)
		if err != nil {
			c.enqueue(errorEvent("send_failed", err.Error()))
			return
		}
		ack := map[string]any{"clientMessageId": payload.ClientMessageID, "message": msg}
		c.enqueue(map[string]any{"type": "message.sent", "payload": ack})
		c.hub.SendTo(payload.ToUserID, "message.created", msg)
		if c.hub.IsOnline(payload.ToUserID) {
			if delivered, err := c.hub.app.MarkDelivered(context.Background(), msg.ID, payload.ToUserID); err == nil {
				c.enqueue(map[string]any{"type": "message.delivered", "payload": delivered})
			}
		}
	case "receipt.delivered":
		var payload struct {
			MessageID string `json:"messageId"`
		}
		if decode(event.Payload, &payload) == nil {
			if msg, err := c.hub.app.MarkDelivered(context.Background(), payload.MessageID, c.userID); err == nil {
				c.hub.SendTo(msg.SenderID, "message.delivered", msg)
			}
		}
	case "receipt.read":
		var payload struct {
			MessageID string `json:"messageId"`
		}
		if decode(event.Payload, &payload) == nil {
			if msg, err := c.hub.app.MarkRead(context.Background(), payload.MessageID, c.userID); err == nil {
				c.hub.SendTo(msg.SenderID, "message.read", msg)
				c.enqueue(map[string]any{"type": "message.read", "payload": msg})
			}
		}
	case "typing":
		var payload struct {
			ToUserID string `json:"toUserId"`
			IsTyping bool   `json:"isTyping"`
		}
		if decode(event.Payload, &payload) == nil {
			c.hub.SendTo(payload.ToUserID, "typing", map[string]any{"fromUserId": c.userID, "isTyping": payload.IsTyping})
		}
	default:
		c.enqueue(errorEvent("unknown_event", "unsupported websocket event"))
	}
}

func BroadcastMessage(h *Hub, msg domain.Message) {
	h.SendTo(msg.ReceiverID, "message.created", msg)
}

func decode(raw json.RawMessage, out any) error {
	if len(raw) == 0 {
		raw = []byte("{}")
	}
	return json.Unmarshal(raw, out)
}

func errorEvent(code, message string) map[string]any {
	slog.Debug("websocket error", "code", code, "message", message)
	return map[string]any{"type": "error", "payload": map[string]string{"code": code, "message": message}}
}
