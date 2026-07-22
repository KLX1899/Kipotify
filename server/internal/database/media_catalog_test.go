package database

import (
	"os"
	"path/filepath"
	"testing"
)

func TestDiscoverMediaTracks(t *testing.T) {
	root := t.TempDir()
	trackPath := filepath.Join(root, "TaylorSwift", "TheTorturedPoestDepartment", "ICanDoItWithABrokenHeart.mp3")
	if err := os.MkdirAll(filepath.Dir(trackPath), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(trackPath, []byte("test"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "ignore.txt"), []byte("test"), 0o644); err != nil {
		t.Fatal(err)
	}

	tracks, err := discoverMediaTracks(root)
	if err != nil {
		t.Fatal(err)
	}
	if len(tracks) != 1 {
		t.Fatalf("got %d tracks, want 1", len(tracks))
	}
	track := tracks[0]
	if track.artistName != "Taylor Swift" || track.albumTitle != "The Tortured Poest Department" || track.title != "I Can Do It With A Broken Heart" {
		t.Fatalf("unexpected metadata: %+v", track)
	}
	if track.path != "media/audio/TaylorSwift/TheTorturedPoestDepartment/ICanDoItWithABrokenHeart.mp3" {
		t.Fatalf("unexpected media path: %s", track.path)
	}
}

func TestMediaMatchKeyIgnoresFilenameFormatting(t *testing.T) {
	got := mediaMatchKey("TaylorSwift", "BlankSpace")
	want := mediaMatchKey("Taylor Swift", "Blank Space")
	if got != want {
		t.Fatalf("got %q, want %q", got, want)
	}
}
