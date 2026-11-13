'use strict';

const http = require('node:http');
const { createApp } = require('./app');

const config = {
  clientId: process.env.LINKEDIN_MEMBER_CLIENT_ID,
  clientSecret: process.env.LINKEDIN_MEMBER_CLIENT_SECRET,
  publicUrl: process.env.BFF_PUBLIC_URL,
  appRedirectUri: process.env.APP_REDIRECT_URI || 'com.lttbdd.socialagent://oauth/linkedin/callback',
};

let handler;
try {
  handler = createApp({ config });
} catch (error) {
  console.error(error.message);
  process.exit(1);
}

const port = Number(process.env.PORT) || 8787;
http.createServer(handler).listen(port, () => console.log(`LinkedIn BFF đang chạy ở cổng ${port}`));
