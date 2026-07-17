package domain

import "time"

type User struct {
	ID             string     `json:"id"`
	Name           string     `json:"name"`
	Email          string     `json:"email"`
	AvatarURL      string     `json:"avatarUrl"`
	IsPremium      bool       `json:"isPremium"`
	PremiumExpires *time.Time `json:"premiumExpiresAt,omitempty"`
	Language       string     `json:"language"`
	Theme          string     `json:"theme"`
	FollowersCount int        `json:"followersCount"`
	FollowingCount int        `json:"followingCount"`
	CreatedAt      time.Time  `json:"createdAt"`
}

type PublicUser struct {
	ID                 string `json:"id"`
	Name               string `json:"name"`
	Email              string `json:"email,omitempty"`
	AvatarURL          string `json:"avatarUrl"`
	IsPremium          bool   `json:"isPremium"`
	IsFollowing        bool   `json:"isFollowing"`
	PublicPlaylistName string `json:"publicPlaylistName,omitempty"`
	FollowersCount     int    `json:"followersCount"`
	FollowingCount     int    `json:"followingCount"`
}

type Artist struct {
	ID        string    `json:"id"`
	Name      string    `json:"name"`
	AvatarURL string    `json:"avatarUrl"`
	Bio       string    `json:"bio"`
	CreatedAt time.Time `json:"createdAt"`
}

type Album struct {
	ID            string    `json:"id"`
	Title         string    `json:"title"`
	ArtistID      string    `json:"artistId"`
	ArtistName    string    `json:"artistName"`
	CoverImageURL string    `json:"coverImageUrl"`
	ReleaseDate   time.Time `json:"releaseDate"`
	CreatedAt     time.Time `json:"createdAt"`
}

type Track struct {
	ID              string    `json:"id"`
	Title           string    `json:"title"`
	ArtistID        string    `json:"artistId,omitempty"`
	ArtistName      string    `json:"artistName"`
	AlbumID         string    `json:"albumId,omitempty"`
	AlbumTitle      string    `json:"albumTitle,omitempty"`
	CoverImageURL   string    `json:"coverImageUrl"`
	AudioURL        string    `json:"audioUrl"`
	Genre           string    `json:"genre"`
	Locale          string    `json:"locale"`
	DurationSeconds int       `json:"durationSeconds"`
	Lyric           string    `json:"lyric"`
	PlayCount       int       `json:"playCount"`
	DownloadCount   int       `json:"downloadCount"`
	IsLiked         bool      `json:"isLiked"`
	IsDownloaded    bool      `json:"isDownloaded"`
	LocalFilePath   *string   `json:"localFilePath,omitempty"`
	CreatedAt       time.Time `json:"createdAt"`
}

type Playlist struct {
	ID            string    `json:"id"`
	OwnerID       *string   `json:"ownerId,omitempty"`
	OwnerName     *string   `json:"ownerName,omitempty"`
	Name          string    `json:"name"`
	Description   string    `json:"description"`
	CoverImageURL string    `json:"coverImageUrl"`
	Visibility    string    `json:"visibility"`
	Category      string    `json:"category"`
	TrackCount    int       `json:"trackCount"`
	CreatedAt     time.Time `json:"createdAt"`
}

type Notification struct {
	ID        string    `json:"id"`
	Type      string    `json:"type"`
	Title     string    `json:"title"`
	Body      string    `json:"body"`
	IsRead    bool      `json:"isRead"`
	CreatedAt time.Time `json:"createdAt"`
}

type SharedTrackCard struct {
	ID            string `json:"id"`
	Title         string `json:"title"`
	ArtistName    string `json:"artistName"`
	CoverImageURL string `json:"coverImageUrl"`
	AudioURL      string `json:"audioUrl"`
}

type Message struct {
	ID          string           `json:"id"`
	SenderID    string           `json:"senderId"`
	SenderName  string           `json:"senderName"`
	ReceiverID  string           `json:"receiverId,omitempty"`
	Content     string           `json:"content"`
	Timestamp   int64            `json:"timestamp"`
	Status      string           `json:"status"`
	SharedTrack *Track           `json:"sharedTrack,omitempty"`
	SongCard    *SharedTrackCard `json:"songCard,omitempty"`
	DeliveredAt *time.Time       `json:"deliveredAt,omitempty"`
	ReadAt      *time.Time       `json:"readAt,omitempty"`
}

type Page struct {
	Page       int  `json:"page"`
	Limit      int  `json:"limit"`
	Total      int  `json:"total"`
	TotalPages int  `json:"totalPages"`
	HasNext    bool `json:"hasNext"`
}

type Paged[T any] struct {
	Data T    `json:"data"`
	Page Page `json:"page"`
}

type TrackFilters struct {
	Query    string
	Genre    string
	Locale   string
	Section  string
	ArtistID string
	Page     int
	Limit    int
}

type PlaylistFilters struct {
	Category string
	Mine     bool
	Followed bool
	Page     int
	Limit    int
}

type SearchResults struct {
	Songs     Paged[[]Track]      `json:"songs"`
	Artists   Paged[[]Artist]     `json:"artists"`
	Users     Paged[[]PublicUser] `json:"users"`
	Playlists Paged[[]Playlist]   `json:"playlists"`
}
