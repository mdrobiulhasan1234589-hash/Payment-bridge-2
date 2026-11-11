-- Payment Bridge — run this ONCE in Supabase → SQL Editor
create table if not exists public.payments (
  id           bigint generated always as identity primary key,
  trx_id       text not null unique,          -- bKash/Nagad/Rocket TrxID (unique = no double credit)
  method       text not null,                 -- bkash | nagad | rocket
  amount       numeric(12,2) not null,
  sender_phone text,
  message      text,
  status       text not null default 'RECEIVED',   -- you can change to USED / MATCHED etc. in your own logic
  created_at   timestamptz not null default now()
);

create index if not exists payments_created_idx on public.payments (created_at desc);

-- Only the edge function (service role) can touch this table.
alter table public.payments enable row level security;
