create table if not exists artists (
    id uuid primary key default gen_random_uuid(),
    name text not null,
    slug text not null unique,
    bio text not null default '',
    profile_image_path text not null default '',
    country text,
    birth_date date,
    active_years text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    check (name <> ''),
    check (slug <> '')
);

create table if not exists releases (
    id uuid primary key default gen_random_uuid(),
    title text not null,
    slug text not null,
    release_type text not null check (release_type in ('album','single','ep')),
    release_date date,
    primary_artist_id uuid not null references artists(id) on delete restrict,
    cover_image_path text not null default '',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (primary_artist_id, slug),
    check (title <> ''),
    check (slug <> '')
);

alter table tracks add column if not exists slug text;
alter table tracks add column if not exists release_id uuid references releases(id) on delete set null;
alter table tracks add column if not exists track_number int;
alter table tracks add column if not exists disc_number int not null default 1;
alter table tracks add column if not exists audio_file_path text not null default '';
alter table tracks add column if not exists lyrics_file_path text not null default '';
alter table tracks add column if not exists release_date date;
alter table tracks add column if not exists is_explicit boolean not null default false;
alter table tracks add column if not exists artwork_source text not null default 'embedded_audio';

do $$
begin
    if not exists (select 1 from pg_constraint where conname = 'tracks_disc_number_positive') then
        alter table tracks add constraint tracks_disc_number_positive check (disc_number > 0);
    end if;
    if not exists (select 1 from pg_constraint where conname = 'tracks_track_number_positive') then
        alter table tracks add constraint tracks_track_number_positive check (track_number is null or track_number > 0);
    end if;
    if not exists (select 1 from pg_constraint where conname = 'tracks_artwork_source_valid') then
        alter table tracks add constraint tracks_artwork_source_valid check (artwork_source in ('embedded_audio','release_cover','artist_image','external'));
    end if;
end $$;

create table if not exists track_artists (
    track_id text not null references tracks(id) on delete cascade,
    artist_id uuid not null references artists(id) on delete restrict,
    role text not null check (role in ('primary','featured','producer','writer')),
    position int not null default 1,
    created_at timestamptz not null default now(),
    primary key (track_id, artist_id, role)
);

comment on column tracks.audio_file_path is 'Relative path under server/, for example media/audio/{artist-slug}/{release-slug}/{track-file}.mp3. Do not store binary audio in the database.';
comment on column tracks.lyrics_file_path is 'Relative path under server/, for example media/lyrics/{artist-slug}/{release-slug}/{track-file}.lrc.';
comment on column tracks.artwork_source is 'Default embedded_audio means Android should read playback artwork from the audio file metadata. Use releases.cover_image_path, then artists.profile_image_path as fallback artwork.';

with artist_names as (
    select distinct nullif(trim(artist_name), '') as name
    from tracks
), artist_slugs as (
    select
        name,
        coalesce(nullif(trim(both '-' from regexp_replace(lower(name), '[^a-z0-9]+', '-', 'g')), ''), 'unknown-artist') as base_slug
    from artist_names
    where name is not null
), artist_deduped as (
    select
        name,
        case when count(*) over (partition by base_slug) = 1
            then base_slug
            else base_slug || '-' || substr(md5(name), 1, 8)
        end as slug
    from artist_slugs
)
insert into artists(name, slug, bio, profile_image_path)
select name, slug, '', 'media/images/artists/' || slug || '.jpg'
from artist_deduped
on conflict (slug) do update set
    name = excluded.name,
    updated_at = now();

with release_inputs as (
    select distinct
        a.id as artist_id,
        a.slug as artist_slug,
        coalesce(nullif(trim(t.album_title), ''), trim(t.title)) as title,
        case when nullif(trim(t.album_title), '') is null then 'single' else 'album' end as release_type
    from tracks t
    join artists a on a.name = t.artist_name
), release_slugs as (
    select
        artist_id,
        artist_slug,
        title,
        release_type,
        coalesce(nullif(trim(both '-' from regexp_replace(lower(title), '[^a-z0-9]+', '-', 'g')), ''), 'untitled-release') as base_slug
    from release_inputs
), release_deduped as (
    select
        artist_id,
        title,
        release_type,
        case when count(*) over (partition by artist_id, base_slug) = 1
            then base_slug
            else base_slug || '-' || substr(md5(title), 1, 8)
        end as slug
    from release_slugs
)
insert into releases(title, slug, release_type, primary_artist_id, cover_image_path)
select title, slug, release_type, artist_id, 'media/images/releases/' || slug || '.jpg'
from release_deduped
on conflict (primary_artist_id, slug) do update set
    title = excluded.title,
    release_type = excluded.release_type,
    updated_at = now();

update tracks t
set release_id = r.id
from artists a
join releases r on r.primary_artist_id = a.id
where a.name = t.artist_name
  and r.title = coalesce(nullif(trim(t.album_title), ''), trim(t.title))
  and t.release_id is null;

with numbered as (
    select
        id,
        row_number() over (
            partition by release_id
            order by created_at, case when id ~ '^[0-9]+$' then id::int else null end nulls last, id
        ) as rn
    from tracks
), track_catalog as (
    select
        t.id,
        numbered.rn,
        r.release_date,
        r.slug as release_slug,
        a.slug as artist_slug
    from tracks t
    join numbered on numbered.id = t.id
    left join releases r on r.id = t.release_id
    left join artists a on a.id = r.primary_artist_id
)
update tracks t
set
    slug = coalesce(nullif(t.slug, ''), coalesce(nullif(trim(both '-' from regexp_replace(lower(t.title), '[^a-z0-9]+', '-', 'g')), ''), 'untitled-track') || '-' || substr(md5(t.id), 1, 8)),
    track_number = coalesce(t.track_number, track_catalog.rn),
    release_date = coalesce(t.release_date, track_catalog.release_date),
    audio_file_path = case when t.audio_file_path = '' then
        'media/audio/' || coalesce(track_catalog.artist_slug, 'unknown-artist') || '/' || coalesce(track_catalog.release_slug, 'singles') || '/' ||
        coalesce(nullif(trim(both '-' from regexp_replace(lower(t.title), '[^a-z0-9]+', '-', 'g')), ''), 'track') || '.mp3'
        else t.audio_file_path end,
    lyrics_file_path = case when t.lyrics_file_path = '' and nullif(t.lyric, '') is not null then
        'media/lyrics/' || coalesce(track_catalog.artist_slug, 'unknown-artist') || '/' || coalesce(track_catalog.release_slug, 'singles') || '/' ||
        coalesce(nullif(trim(both '-' from regexp_replace(lower(t.title), '[^a-z0-9]+', '-', 'g')), ''), 'track') || '.lrc'
        else t.lyrics_file_path end
from track_catalog
where t.id = track_catalog.id;

insert into track_artists(track_id, artist_id, role, position)
select t.id, a.id, 'primary', 1
from tracks t
join artists a on a.name = t.artist_name
on conflict do nothing;

create unique index if not exists idx_tracks_release_slug_unique on tracks(release_id, slug) where release_id is not null;
create unique index if not exists idx_tracks_release_position_unique on tracks(release_id, disc_number, track_number) where release_id is not null and track_number is not null;
create index if not exists idx_artists_name_trgm on artists using gin(name gin_trgm_ops);
create index if not exists idx_artists_slug on artists(slug);
create index if not exists idx_releases_artist_date on releases(primary_artist_id, release_date desc nulls last, created_at desc);
create index if not exists idx_releases_type_date on releases(release_type, release_date desc nulls last, created_at desc);
create index if not exists idx_releases_title_trgm on releases using gin(title gin_trgm_ops);
create index if not exists idx_tracks_release on tracks(release_id, disc_number, track_number);
create index if not exists idx_tracks_release_date on tracks(release_date desc nulls last, created_at desc);
create index if not exists idx_track_artists_artist_role on track_artists(artist_id, role, track_id);
create index if not exists idx_track_artists_track_role on track_artists(track_id, role, position);
