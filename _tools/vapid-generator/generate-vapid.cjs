const webpush = require("web-push");
const crypto = require("crypto");

const keys = webpush.generateVAPIDKeys();
const privateBytes = Buffer.from(keys.privateKey, "base64url");

const ecdh = crypto.createECDH("prime256v1");
ecdh.setPrivateKey(privateBytes);

const derivedPublic = ecdh
  .getPublicKey(null, "uncompressed")
  .toString("base64url");

if (derivedPublic !== keys.publicKey) {
  throw new Error("VAPID key pair validation failed.");
}

process.stdout.write(JSON.stringify(keys));