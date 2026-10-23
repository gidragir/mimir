// scrypt (N=16384, r=8) на js заметно дольше, чем на jvm: стандартные 2 с mocha не хватает.
config.client = config.client || {};
config.client.mocha = { timeout: 120000 };
