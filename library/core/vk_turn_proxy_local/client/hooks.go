package vkturnclient

import "sync"

var captchaURLHook struct {
	mu sync.Mutex
	fn func(string)
}

func SetCaptchaURLHandler(fn func(string)) {
	captchaURLHook.mu.Lock()
	captchaURLHook.fn = fn
	captchaURLHook.mu.Unlock()
}

func notifyCaptchaURL(url string) {
	captchaURLHook.mu.Lock()
	fn := captchaURLHook.fn
	captchaURLHook.mu.Unlock()
	if fn != nil {
		fn(url)
	}
}
