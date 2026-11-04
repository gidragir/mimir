config.set({
    customLaunchers: {
        ChromeHeadlessCI: {
            base: 'ChromeHeadless',
            flags: [
                '--no-sandbox',
                '--disable-setuid-sandbox',
                '--disable-dev-shm-usage',
                '--disable-gpu',
                '--headless=new'
            ]
        }
    },
    browsers: ['ChromeHeadlessCI']
});

config.client = config.client || {};
config.client.mocha = { timeout: 120000 };
