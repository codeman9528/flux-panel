package socket

import (
	"os"

	"github.com/go-gost/x/config"
)

func saveConfig() {

	file := "gost.json"

	// gost.json 含全部转发配置，权限收紧为 0600
	f, err := os.OpenFile(file, os.O_RDWR|os.O_CREATE|os.O_TRUNC, 0600)
	if err != nil {
		return
	}
	defer f.Close()

	if err := config.Global().Write(f, "json"); err != nil {

		return
	}

	return
}
