alter table tracks add column if not exists artist_name text not null default '';
alter table tracks add column if not exists album_title text not null default '';
alter table tracks add column if not exists duration int not null default 180;
alter table tracks add column if not exists lyric text not null default '';

alter table playlist_tracks drop constraint if exists playlist_tracks_track_id_fkey;
alter table liked_tracks drop constraint if exists liked_tracks_track_id_fkey;
alter table recently_played drop constraint if exists recently_played_track_id_fkey;
alter table downloads drop constraint if exists downloads_track_id_fkey;
alter table messages drop constraint if exists messages_shared_track_id_fkey;

alter table playlist_tracks alter column track_id type text using track_id::text;
alter table liked_tracks alter column track_id type text using track_id::text;
alter table recently_played alter column track_id type text using track_id::text;
alter table downloads alter column track_id type text using track_id::text;
alter table messages alter column shared_track_id type text using shared_track_id::text;
alter table tracks alter column id type text using id::text;

do $$
begin
    if exists (
        select 1 from information_schema.columns
        where table_schema = 'public' and table_name = 'tracks' and column_name = 'artist_id'
    ) and to_regclass('public.artists') is not null then
        update tracks t
        set artist_name = coalesce(nullif(t.artist_name, ''), ar.name)
        from artists ar
        where t.artist_id = ar.id;
    end if;

    if exists (
        select 1 from information_schema.columns
        where table_schema = 'public' and table_name = 'tracks' and column_name = 'album_id'
    ) and to_regclass('public.albums') is not null then
        update tracks t
        set album_title = coalesce(nullif(t.album_title, ''), al.title)
        from albums al
        where t.album_id = al.id;
    end if;

    if exists (
        select 1 from information_schema.columns
        where table_schema = 'public' and table_name = 'tracks' and column_name = 'duration_seconds'
    ) then
        update tracks set duration = duration_seconds;
    end if;
end $$;

update tracks set artist_name = 'Unknown Artist' where artist_name = '';
update tracks set album_title = '' where album_title is null;
update tracks set duration = 180 where duration is null or duration <= 0;
update tracks set lyric = '' where lyric is null;

drop index if exists idx_tracks_artist_created;
drop index if exists idx_tracks_genre_created;
drop index if exists idx_tracks_locale_created;
drop index if exists idx_artists_name_trgm;

alter table tracks drop column if exists artist_id;
alter table tracks drop column if exists album_id;
alter table tracks drop column if exists cover_image_url;
alter table tracks drop column if exists audio_url;
alter table tracks drop column if exists genre;
alter table tracks drop column if exists locale;
alter table tracks drop column if exists duration_seconds;

drop table if exists albums;
drop table if exists artists;

alter table tracks alter column artist_name set not null;
alter table tracks alter column album_title set not null;
alter table tracks alter column duration set not null;
alter table tracks alter column lyric set not null;

alter table playlist_tracks add constraint playlist_tracks_track_id_fkey foreign key (track_id) references tracks(id) on delete cascade;
alter table liked_tracks add constraint liked_tracks_track_id_fkey foreign key (track_id) references tracks(id) on delete cascade;
alter table recently_played add constraint recently_played_track_id_fkey foreign key (track_id) references tracks(id) on delete cascade;
alter table downloads add constraint downloads_track_id_fkey foreign key (track_id) references tracks(id) on delete cascade;
alter table messages add constraint messages_shared_track_id_fkey foreign key (shared_track_id) references tracks(id) on delete set null;

do $$
begin
    if not exists (
        select 1 from pg_constraint where conname = 'tracks_duration_positive'
    ) then
        alter table tracks add constraint tracks_duration_positive check (duration > 0);
    end if;
end $$;

create index if not exists idx_tracks_created on tracks(created_at desc);
create index if not exists idx_tracks_play_count on tracks(play_count desc, created_at desc);
create index if not exists idx_tracks_title_trgm on tracks using gin(title gin_trgm_ops);
create index if not exists idx_tracks_artist_name_trgm on tracks using gin(artist_name gin_trgm_ops);
create index if not exists idx_tracks_album_title_trgm on tracks using gin(album_title gin_trgm_ops);
