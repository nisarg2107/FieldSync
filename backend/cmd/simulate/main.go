package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"time"

	"fieldsync/internal/simulation"
)

func main() {
	var config simulation.Config
	flag.Int64Var(&config.Seed, "seed", 42, "repeatable random seed")
	flag.IntVar(&config.Replicas, "replicas", 3, "replica count")
	flag.IntVar(&config.Edits, "edits", 120, "offline edits per replica")
	flag.Float64Var(&config.FailureRate, "loss", 0.6, "fault intensity (half applied at each request/response boundary)")
	flag.DurationVar(&config.Delay, "delay", 2*time.Millisecond, "injected latency per request")
	output := flag.String("out", "", "optional JSON report path")
	flag.Parse()
	report, err := simulation.Run(config)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	data, err := json.MarshalIndent(report, "", "  ")
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	fmt.Println(string(data))
	if *output != "" {
		if err = os.WriteFile(*output, append(data, '\n'), 0644); err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
	}
	if !report.Converged {
		os.Exit(1)
	}
}
