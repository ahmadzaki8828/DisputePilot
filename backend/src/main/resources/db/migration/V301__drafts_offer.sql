-- Session E: the settlement an OFFER draft proposes ({amount {currency, value}, offerType}), sent through
-- PayPal's make-offer call once the draft is approved. Null for every other recommendation.
alter table drafts add column offer jsonb;
