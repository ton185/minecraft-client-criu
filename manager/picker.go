package main

// The checkpoint picker.
//
// Shown before the game starts, when there is no game window to put it in, so it
// is drawn directly on X. Deliberately plain: a list, three buttons and a
// checkbox, in the server's "fixed" font.

import (
	"fmt"
	"time"
)

type PickItem struct {
	Generation int
	When       string
	Size       string
	// Restorable is false when the image's fingerprint does not match this
	// instance. Such a row is still listed and still deletable: making it simply
	// vanish would leave the user with several GB of invisible, unreclaimable
	// disk and no explanation for where their checkpoints went.
	Restorable bool
	Why        string
}

type PickerInput struct {
	Items  []PickItem
	Notice string // shown above the list; empty for the ordinary case
}

type PickKind int

const (
	PickRestore PickKind = iota
	PickSkip
	PickDelete
	PickQuit
)

type PickResult struct {
	Kind         PickKind
	Generation   int
	AlwaysLatest bool
}

const (
	pkRowH   = 22
	pkPadX   = 14
	pkFontY  = 15 // baseline offset within a row
	pkBtnH   = 26
	pkWidth  = 560
)

type hotspot struct {
	x, y, w, h int
	kind       PickKind
	gen        int
	toggle     bool
	// button distinguishes the action buttons from the list rows. This used to be
	// inferred as `y >= listH`, comparing an absolute coordinate against a HEIGHT:
	// every row satisfied it, so clicking a row restored immediately instead of
	// selecting it, and a misclick in the list was an instant restore.
	button bool
}

func (h hotspot) hit(px, py int) bool {
	return px >= h.x && px < h.x+h.w && py >= h.y && py < h.y+h.h
}

// RunPicker shows the dialog and blocks until the user chooses.
func RunPicker(in PickerInput) (PickResult, error) {
	if len(in.Items) == 0 {
		return PickResult{Kind: PickSkip}, nil
	}
	x, err := xOpen()
	if err != nil {
		return PickResult{}, err
	}
	defer x.Close()

	noticeLines := wrapText(in.Notice, (pkWidth-2*pkPadX)/6)
	headerH := 34 + len(noticeLines)*16
	listH := len(in.Items) * pkRowH
	height := headerH + listH + 16 + pkBtnH + 14 + 22

	win, err := x.createWindow(pkWidth, height, "mc-criu — restore a checkpoint?")
	if err != nil {
		return PickResult{}, err
	}
	gc, err := x.createGC(win)
	if err != nil {
		return PickResult{}, err
	}
	if fid, err := x.openFont("fixed"); err == nil {
		x.gcFont(gc, fid)
	}
	if err := x.mapWindow(win); err != nil {
		return PickResult{}, err
	}

	selected := -1 // newest restorable
	for i, it := range in.Items {
		if it.Restorable {
			selected = i
		}
	}
	always := false

	draw := func() []hotspot {
		var spots []hotspot
		x.gcForeground(gc, x.white)
		x.fillRect(win, gc, 0, 0, pkWidth, height)
		x.gcColors(gc, x.black, x.white)

		y := 20
		x.text(win, gc, pkPadX, y, "A saved checkpoint can be restored instead of loading the game.")
		y += 16
		for _, l := range noticeLines {
			x.text(win, gc, pkPadX, y, l)
			y += 16
		}
		y += 6

		for i, it := range in.Items {
			ry := y + i*pkRowH
			label := fmt.Sprintf("#%-3d  %s  %10s", it.Generation, it.When, it.Size)
			if !it.Restorable {
				label = fmt.Sprintf("#%-3d  %s  %10s  (cannot restore: %s)",
					it.Generation, it.When, it.Size, it.Why)
			}
			if i == selected {
				x.gcForeground(gc, x.black)
				x.fillRect(win, gc, pkPadX-4, ry, pkWidth-2*pkPadX+8, pkRowH-2)
				x.gcColors(gc, x.white, x.black) // inverted: white glyphs on the black bar
				x.text(win, gc, pkPadX, ry+pkFontY, label)
				x.gcColors(gc, x.black, x.white)
			} else {
				x.text(win, gc, pkPadX, ry+pkFontY, label)
			}
			// Delete hotspot on the right of every row.
			dx := pkWidth - pkPadX - 60
			x.drawRect(win, gc, dx, ry, 60, pkRowH-2)
			x.text(win, gc, dx+10, ry+pkFontY, "delete")
			// Delete is offered on every row; selecting for restore only on the
			// rows that can actually be restored.
			if it.Restorable {
				spots = append(spots, hotspot{pkPadX - 4, ry, pkWidth - 2*pkPadX - 60,
					pkRowH - 2, PickRestore, it.Generation, false, false})
			}
			spots = append(spots, hotspot{dx, ry, 60, pkRowH - 2, PickDelete, it.Generation, false, false})
		}

		by := y + listH + 12
		btn := func(bx, w int, label string, kind PickKind) hotspot {
			x.drawRect(win, gc, bx, by, w, pkBtnH)
			x.text(win, gc, bx+10, by+17, label)
			return hotspot{bx, by, w, pkBtnH, kind, 0, false, true}
		}
		if selected >= 0 {
			spots = append(spots,
				btn(pkPadX, 150, "Restore selected", PickRestore),
				btn(pkPadX+160, 170, "Skip - load normally", PickSkip))
			spots[len(spots)-2].gen = in.Items[selected].Generation
		} else {
			// Nothing here can be restored; only skipping and deleting make sense.
			spots = append(spots, btn(pkPadX, 170, "Skip - load normally", PickSkip))
		}

		cy := by + pkBtnH + 12
		box := 12
		x.drawRect(win, gc, pkPadX, cy, box, box)
		if always {
			x.fillRect(win, gc, pkPadX+3, cy+3, box-6, box-6)
		}
		x.text(win, gc, pkPadX+box+8, cy+11, "always load the latest, do not ask again")
		spots = append(spots, hotspot{pkPadX, cy, 320, box + 4, PickQuit, 0, true, false})
		return spots
	}

	spots := draw()
	deadline := time.Now().Add(5 * time.Minute)

	for {
		remaining := time.Until(deadline)
		if remaining <= 0 {
			// Nobody is at the keyboard. Loading normally is the safe default.
			return PickResult{Kind: PickSkip}, nil
		}
		ev, err := x.nextEvent(remaining)
		if err != nil {
			// A read timeout, a closed connection or a torn-down window all mean
			// nobody is answering. Loading normally is the safe outcome.
			return PickResult{Kind: PickSkip}, nil
		}
		switch ev[0] & 0x7f {
		case evExpose, evConfigureNotify:
			spots = draw()
		case evKeyPress:
			// keycode 9 is Escape, 36 Return, 111/116 up/down on essentially
			// every Linux X server (evdev keycodes are stable in practice).
			switch ev[1] {
			case 9:
				return PickResult{Kind: PickSkip}, nil
			case 36:
				if selected < 0 {
					return PickResult{Kind: PickSkip}, nil
				}
				return PickResult{Kind: PickRestore,
					Generation: in.Items[selected].Generation, AlwaysLatest: always}, nil
			case 111:
				if selected > 0 {
					selected--
					spots = draw()
				}
			case 116:
				if selected < len(in.Items)-1 {
					selected++
					spots = draw()
				}
			}
		case evButtonPress:
			px := int(int16(uint16(ev[24]) | uint16(ev[25])<<8))
			py := int(int16(uint16(ev[26]) | uint16(ev[27])<<8))
			for _, h := range spots {
				if !h.hit(px, py) {
					continue
				}
				if h.toggle {
					always = !always
					spots = draw()
					break
				}
				switch h.kind {
				case PickDelete:
					return PickResult{Kind: PickDelete, Generation: h.gen}, nil
				case PickSkip:
					return PickResult{Kind: PickSkip}, nil
				case PickRestore:
					if h.gen != 0 {
						// Clicking a row selects it; clicking the button acts.
						for i, it := range in.Items {
							if it.Generation == h.gen {
								selected = i
							}
						}
					}
					if h.button && selected >= 0 {
						return PickResult{Kind: PickRestore,
							Generation: in.Items[selected].Generation, AlwaysLatest: always}, nil
					}
					spots = draw()
				}
				break
			}
		case evClientMessage:
			return PickResult{Kind: PickSkip}, nil
		}
	}
}

// wrapText breaks a notice into lines of at most n characters, on spaces.
func wrapText(s string, n int) []string {
	if s == "" || n <= 0 {
		return nil
	}
	var out []string
	line := ""
	for _, w := range splitSpaces(s) {
		if line == "" {
			line = w
		} else if len(line)+1+len(w) <= n {
			line += " " + w
		} else {
			out = append(out, line)
			line = w
		}
	}
	if line != "" {
		out = append(out, line)
	}
	return out
}

func splitSpaces(s string) []string {
	var out []string
	cur := ""
	for _, r := range s {
		if r == ' ' || r == '\n' || r == '\t' {
			if cur != "" {
				out = append(out, cur)
				cur = ""
			}
			continue
		}
		cur += string(r)
	}
	if cur != "" {
		out = append(out, cur)
	}
	return out
}
