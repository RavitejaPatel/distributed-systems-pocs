# 02 - Retry with exponential backoff and jitter

Goal: when a service is struggling, clients must retry in a way that helps it recover
instead of keeping it down (no retry storms, no synchronized waves).