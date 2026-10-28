-- =====================================================================
-- FCM device tokens + dispatch state for the Android parents' app.
-- ADDITIVE ONLY: creates new objects, does not alter or drop anything existing.
-- Review before running (Supabase > SQL Editor). Re-runnable (IF NOT EXISTS / OR REPLACE).
-- Depends on existing: public.parent_profiles, public.notification_outbox, auth.users.
-- =====================================================================

-- 1) One row per app installation of a parent -------------------------
create table if not exists public.device_tokens (
  id              uuid primary key default gen_random_uuid(),
  parent_user_id  uuid not null references auth.users(id) on delete cascade,
  install_id      text not null check (install_id ~ '^[0-9a-f]{32}$'),
  fcm_token       text not null check (char_length(fcm_token) between 20 and 4096),
  platform        text not null default 'android' check (platform = 'android'),
  app_version     text check (app_version is null or char_length(app_version) <= 32),
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  unique (parent_user_id, install_id),
  unique (fcm_token)
);
create index if not exists device_tokens_parent_idx on public.device_tokens (parent_user_id);
alter table public.device_tokens enable row level security;

drop policy if exists "device_tokens: parent reads own" on public.device_tokens;
create policy "device_tokens: parent reads own" on public.device_tokens
  for select to authenticated using (parent_user_id = auth.uid());
-- No insert/update/delete policies: writes only through the RPCs below.
revoke all on public.device_tokens from anon, authenticated;
grant select on public.device_tokens to authenticated;

-- 2) Dispatch bookkeeping (kept out of notification_outbox on purpose) --
create table if not exists public.fcm_dispatch (
  outbox_id    uuid primary key references public.notification_outbox(id) on delete cascade,
  status       text not null check (status in ('processing','retry','done','failed')),
  attempts     integer not null default 0,
  claimed_at   timestamptz not null default now(),
  finished_at  timestamptz,
  last_error   text
);
alter table public.fcm_dispatch enable row level security;     -- no policies: service_role only
revoke all on public.fcm_dispatch from anon, authenticated;

-- One delivery per (event, device): the unique key is the de-duplication guarantee.
create table if not exists public.fcm_deliveries (
  outbox_id        uuid not null references public.notification_outbox(id) on delete cascade,
  device_token_id  uuid not null references public.device_tokens(id) on delete cascade,
  status           text not null check (status in ('sent','failed')),
  error            text,
  sent_at          timestamptz not null default now(),
  primary key (outbox_id, device_token_id)
);
alter table public.fcm_deliveries enable row level security;   -- no policies: service_role only
revoke all on public.fcm_deliveries from anon, authenticated;

-- 3) RPCs called by the app (through the parent's own JWT) --------------
create or replace function public.register_device_token(p_install_id text, p_token text, p_app_version text default null)
returns void
language plpgsql security definer set search_path = public
as $$
declare v_uid uuid := auth.uid();
begin
  if v_uid is null then raise exception 'not authenticated' using errcode = '28000'; end if;
  if not exists (select 1 from public.parent_profiles pp where pp.user_id = v_uid and not pp.disabled) then
    raise exception 'not an active parent' using errcode = '42501';
  end if;
  if p_install_id is null or p_install_id !~ '^[0-9a-f]{32}$' then raise exception 'bad install id' using errcode = '22023'; end if;
  if p_token is null or char_length(p_token) not between 20 and 4096 then raise exception 'bad token' using errcode = '22023'; end if;

  -- The device now belongs to this parent: drop any link of the same token/installation to someone else
  -- (shared phone, new login). Other devices of the same account are untouched.
  delete from public.device_tokens
   where (fcm_token = p_token or install_id = p_install_id)
     and not (parent_user_id = v_uid and install_id = p_install_id);

  insert into public.device_tokens (parent_user_id, install_id, fcm_token, app_version)
  values (v_uid, p_install_id, p_token, left(p_app_version, 32))
  on conflict (parent_user_id, install_id)
  do update set fcm_token = excluded.fcm_token, app_version = excluded.app_version, updated_at = now();
end $$;

create or replace function public.unregister_device_token(p_install_id text)
returns void
language plpgsql security definer set search_path = public
as $$
begin
  if auth.uid() is null then raise exception 'not authenticated' using errcode = '28000'; end if;
  delete from public.device_tokens where parent_user_id = auth.uid() and install_id = p_install_id;
end $$;

revoke all on function public.register_device_token(text, text, text) from public, anon;
revoke all on function public.unregister_device_token(text) from public, anon;
grant execute on function public.register_device_token(text, text, text) to authenticated;
grant execute on function public.unregister_device_token(text) to authenticated;

-- 4) Atomic claim of outbox events (used only by the send-fcm Edge Function) ----
-- Only events created in the last 24 h are sent: old pending rows are never blasted out later.
create or replace function public.claim_fcm_outbox(p_limit integer default 20)
returns setof public.notification_outbox
language sql security definer set search_path = public
as $$
  with cand as (
    select o.id
      from public.notification_outbox o
      left join public.fcm_dispatch d on d.outbox_id = o.id
     where o.status <> 'cancelled'
       and coalesce(o.scheduled_at, o.created_at) <= now()
       and o.created_at > now() - interval '24 hours'
       and coalesce(d.attempts, 0) < 5
       and (d.outbox_id is null
            or (d.status = 'retry'      and d.claimed_at < now() - interval '1 minute')
            or (d.status = 'processing' and d.claimed_at < now() - interval '5 minutes'))
     order by o.created_at
     limit greatest(1, least(p_limit, 50))
       for update of o skip locked
  ), up as (
    insert into public.fcm_dispatch (outbox_id, status, attempts, claimed_at)
    select id, 'processing', 0, now() from cand
    on conflict (outbox_id) do update
      set status = 'processing', attempts = public.fcm_dispatch.attempts + 1, claimed_at = now()
    returning outbox_id
  )
  select o.* from public.notification_outbox o join up on up.outbox_id = o.id;
$$;
revoke all on function public.claim_fcm_outbox(integer) from public, anon, authenticated;
grant execute on function public.claim_fcm_outbox(integer) to service_role;
