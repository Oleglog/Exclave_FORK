// Package logger provides a simple leveled logging interface.
package logger

import (
	"log"
	"sync/atomic"
)

// verboseEnabled controls whether verbose and debug logging is enabled.
//
//nolint:gochecknoglobals // Global log state is acceptable for CLI tools.
var verboseEnabled atomic.Bool

// SetVerbose enables or disables verbose/debug logging.
func SetVerbose(enabled bool) {
	verboseEnabled.Store(enabled)
}

// IsVerbose returns true if verbose logging is enabled.
func IsVerbose() bool {
	return verboseEnabled.Load()
}

// Info logs an informational message.
func Info(v ...any) {
	log.Print(v...)
}

// Infof logs a formatted informational message.
func Infof(format string, v ...any) {
	log.Printf(format, v...)
}

// Warn logs a warning message.
func Warn(v ...any) {
	log.Print(v...)
}

// Warnf logs a formatted warning message.
func Warnf(format string, v ...any) {
	log.Printf(format, v...)
}

// Error logs an error message.
func Error(v ...any) {
	log.Print(v...)
}

// Errorf logs a formatted error message.
func Errorf(format string, v ...any) {
	log.Printf(format, v...)
}

// Verbosef logs a formatted message if verbose logging is enabled.
func Verbosef(format string, v ...any) {
	if verboseEnabled.Load() {
		log.Printf(format, v...)
	}
}

// Debugf logs a formatted message if verbose logging is enabled.
func Debugf(format string, v ...any) {
	if verboseEnabled.Load() {
		log.Printf(format, v...)
	}
}
