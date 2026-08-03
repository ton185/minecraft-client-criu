package main

// `__picker-demo` draws the picker with fabricated entries and prints the choice.
//
// The dialog speaks the X protocol by hand, so it cannot be exercised by any of
// the session commands without a real checkpoint to offer. This is how it gets
// looked at.

import "fmt"

func runPickerDemo() int {
	res, err := RunPicker(PickerInput{
		Items: []PickItem{
			{Generation: 1, When: "2026-07-31 14:02:11", Size: "9.2 GiB", Restorable: true},
			{Generation: 2, When: "2026-07-31 17:41:56", Size: "9.3 GiB", Restorable: true},
			{Generation: 3, When: "2026-07-31 18:40:18", Size: "8.4 GiB",
				Restorable: false, Why: "mods changed"},
		},
		Notice: "some checkpoints no longer match this instance, so the menu is being shown even though \"always load latest\" is on: the mods have changed since this checkpoint was taken (482 jars then, 483 now)",
	})
	if err != nil {
		fmt.Println("picker error:", err)
		return 1
	}
	fmt.Printf("kind=%d generation=%d alwaysLatest=%v\n", res.Kind, res.Generation, res.AlwaysLatest)
	return 0
}
