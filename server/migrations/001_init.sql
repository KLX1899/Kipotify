create extension if not exists pgcrypto;
create extension if not exists pg_trgm;

create table if not exists users (
    id uuid primary key default gen_random_uuid(),
    name text not null,
    email text not null unique,
    password_hash text not null,
    avatar_url text not null default '',
    is_premium boolean not null default false,
    premium_expires_at timestamptz,
    language text not null default 'en',
    theme text not null default 'system',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table if not exists tracks (
    id text primary key,
    title text not null,
    artist_name text not null,
    album_title text not null default '',
    duration int not null default 180 check (duration > 0),
    lyric text not null default '',
    play_count int not null default 0,
    download_count int not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table if not exists playlists (
    id uuid primary key default gen_random_uuid(),
    owner_id uuid references users(id) on delete cascade,
    name text not null,
    description text not null default '',
    cover_image_url text not null default '',
    visibility text not null default 'public' check (visibility in ('public','private')),
    category text not null default 'user' check (category in ('global','local','user')),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table if not exists playlist_tracks (
    playlist_id uuid not null references playlists(id) on delete cascade,
    track_id text not null references tracks(id) on delete cascade,
    position int not null default 0,
    added_at timestamptz not null default now(),
    primary key (playlist_id, track_id)
);

create table if not exists liked_tracks (
    user_id uuid not null references users(id) on delete cascade,
    track_id text not null references tracks(id) on delete cascade,
    created_at timestamptz not null default now(),
    primary key (user_id, track_id)
);

create table if not exists recently_played (
    user_id uuid not null references users(id) on delete cascade,
    track_id text not null references tracks(id) on delete cascade,
    played_at timestamptz not null default now(),
    primary key (user_id, track_id)
);

create table if not exists downloads (
    user_id uuid not null references users(id) on delete cascade,
    track_id text not null references tracks(id) on delete cascade,
    created_at timestamptz not null default now(),
    primary key (user_id, track_id)
);

create table if not exists follows (
    follower_id uuid not null references users(id) on delete cascade,
    followed_id uuid not null references users(id) on delete cascade,
    created_at timestamptz not null default now(),
    primary key (follower_id, followed_id),
    check (follower_id <> followed_id)
);

create table if not exists notifications (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references users(id) on delete cascade,
    type text not null,
    title text not null,
    body text not null default '',
    is_read boolean not null default false,
    created_at timestamptz not null default now()
);

create table if not exists messages (
    id uuid primary key default gen_random_uuid(),
    sender_id uuid not null references users(id) on delete cascade,
    receiver_id uuid not null references users(id) on delete cascade,
    content text not null default '',
    status text not null default 'sent' check (status in ('sending','sent','delivered','read')),
    shared_track_id text references tracks(id) on delete set null,
    created_at timestamptz not null default now(),
    delivered_at timestamptz,
    read_at timestamptz,
    check (sender_id <> receiver_id)
);

create index if not exists idx_tracks_created on tracks(created_at desc);
create index if not exists idx_tracks_play_count on tracks(play_count desc, created_at desc);
create index if not exists idx_tracks_title_trgm on tracks using gin(title gin_trgm_ops);
create index if not exists idx_tracks_artist_name_trgm on tracks using gin(artist_name gin_trgm_ops);
create index if not exists idx_tracks_album_title_trgm on tracks using gin(album_title gin_trgm_ops);
create index if not exists idx_users_name_trgm on users using gin(name gin_trgm_ops);
create index if not exists idx_playlists_name_trgm on playlists using gin(name gin_trgm_ops);
create index if not exists idx_playlist_tracks_lookup on playlist_tracks(playlist_id, position, track_id);
create index if not exists idx_liked_tracks_user_created on liked_tracks(user_id, created_at desc);
create index if not exists idx_recent_user_played on recently_played(user_id, played_at desc);
create index if not exists idx_downloads_user_created on downloads(user_id, created_at desc);
create index if not exists idx_follows_follower on follows(follower_id, created_at desc);
create index if not exists idx_follows_followed on follows(followed_id, created_at desc);
create index if not exists idx_notifications_user_created on notifications(user_id, is_read, created_at desc);
create index if not exists idx_messages_conversation on messages(least(sender_id, receiver_id), greatest(sender_id, receiver_id), created_at desc);
create index if not exists idx_messages_receiver_status on messages(receiver_id, status, created_at desc);
