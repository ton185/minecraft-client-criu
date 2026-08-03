package main

// A minimal X11 client, spoken directly over the wire.
//
// Why not a toolkit: the whole point of the manager is one static binary, and
// every GUI toolkit is a dynamic dependency (and cgo). The core X protocol is
// small enough to write the handful of requests a dialog needs, and using a
// SERVER-SIDE core font ("fixed") means no font has to be bundled or rasterised
// either. X11 only is fine here: Minecraft 1.21.1 ships LWJGL 3.3.3, whose only
// GLFW native is X11, so if the game can open a window this can too — including
// on a Wayland desktop, through XWayland.

import (
	"encoding/binary"
	"errors"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

type xconn struct {
	c            net.Conn
	buf          []byte
	rootWindow   uint32
	rootVisual   uint32
	rootDepth    byte
	white, black uint32
	idBase, idMask uint32
	nextID       uint32
	seq          uint16
}

// X event codes we care about.
const (
	evKeyPress        = 2
	evButtonPress     = 4
	evExpose          = 12
	evConfigureNotify = 22
	evClientMessage   = 33
)

func xDial() (net.Conn, int, error) {
	disp := os.Getenv("DISPLAY")
	if disp == "" {
		return nil, 0, errors.New("DISPLAY is not set")
	}
	// :N, :N.S, host:N — only the local unix-socket forms are supported.
	d := disp
	if i := strings.IndexByte(d, ':'); i >= 0 {
		if i > 0 && d[:i] != "" && d[:i] != "unix" {
			return nil, 0, fmt.Errorf("remote display %q is not supported", disp)
		}
		d = d[i+1:]
	}
	if i := strings.IndexByte(d, '.'); i >= 0 {
		d = d[:i]
	}
	n, err := strconv.Atoi(d)
	if err != nil {
		return nil, 0, fmt.Errorf("cannot parse DISPLAY %q", disp)
	}
	c, err := net.Dial("unix", fmt.Sprintf("/tmp/.X11-unix/X%d", n))
	if err != nil {
		return nil, 0, err
	}
	return c, n, nil
}

// xauthCookie finds the MIT-MAGIC-COOKIE-1 for this display in ~/.Xauthority.
// Without it most servers reject the connection outright.
func xauthCookie(display int) (name string, data []byte) {
	path := os.Getenv("XAUTHORITY")
	if path == "" {
		home, err := os.UserHomeDir()
		if err != nil {
			return "", nil
		}
		path = filepath.Join(home, ".Xauthority")
	}
	b, err := os.ReadFile(path)
	if err != nil {
		return "", nil
	}
	rd := func(p *int, n int) []byte {
		if *p+n > len(b) {
			*p = len(b) + 1
			return nil
		}
		v := b[*p : *p+n]
		*p += n
		return v
	}
	u16 := func(p *int) int {
		v := rd(p, 2)
		if v == nil {
			return -1
		}
		return int(binary.BigEndian.Uint16(v))
	}
	for p := 0; p < len(b); {
		if u16(&p) < 0 { // family
			break
		}
		al := u16(&p)
		if al < 0 {
			break
		}
		rd(&p, al) // address
		nl := u16(&p)
		if nl < 0 {
			break
		}
		num := string(rd(&p, nl))
		ml := u16(&p)
		if ml < 0 {
			break
		}
		mname := string(rd(&p, ml))
		dl := u16(&p)
		if dl < 0 {
			break
		}
		mdata := rd(&p, dl)
		if mdata == nil {
			break
		}
		if mname == "MIT-MAGIC-COOKIE-1" && (num == strconv.Itoa(display) || num == "") {
			out := make([]byte, len(mdata))
			copy(out, mdata)
			return mname, out
		}
	}
	return "", nil
}

func pad4(n int) int { return (4 - n%4) % 4 }

func xOpen() (*xconn, error) {
	c, display, err := xDial()
	if err != nil {
		return nil, err
	}
	name, cookie := xauthCookie(display)

	setup := make([]byte, 0, 64)
	setup = append(setup, 'l', 0)                      // little-endian
	setup = binary.LittleEndian.AppendUint16(setup, 11) // protocol major
	setup = binary.LittleEndian.AppendUint16(setup, 0)  // protocol minor
	setup = binary.LittleEndian.AppendUint16(setup, uint16(len(name)))
	setup = binary.LittleEndian.AppendUint16(setup, uint16(len(cookie)))
	setup = append(setup, 0, 0)
	setup = append(setup, name...)
	setup = append(setup, make([]byte, pad4(len(name)))...)
	setup = append(setup, cookie...)
	setup = append(setup, make([]byte, pad4(len(cookie)))...)
	if _, err := c.Write(setup); err != nil {
		c.Close()
		return nil, err
	}

	head := make([]byte, 8)
	if _, err := ioReadFull(c, head); err != nil {
		c.Close()
		return nil, err
	}
	replyLen := int(binary.LittleEndian.Uint16(head[6:8])) * 4
	body := make([]byte, replyLen)
	if _, err := ioReadFull(c, body); err != nil {
		c.Close()
		return nil, err
	}
	if head[0] == 0 {
		reason := string(body[:min(int(head[1]), len(body))])
		c.Close()
		return nil, fmt.Errorf("X server refused the connection: %s", reason)
	}

	x := &xconn{c: c}
	x.idBase = binary.LittleEndian.Uint32(body[4:8])
	x.idMask = binary.LittleEndian.Uint32(body[8:12])
	vendorLen := int(binary.LittleEndian.Uint16(body[16:18]))
	numFormats := int(body[21])
	p := 32 + vendorLen + pad4(vendorLen) + 8*numFormats
	if p+40 > len(body) {
		c.Close()
		return nil, errors.New("malformed X setup reply")
	}
	// First SCREEN record.
	x.rootWindow = binary.LittleEndian.Uint32(body[p : p+4])
	x.white = binary.LittleEndian.Uint32(body[p+8 : p+12])
	x.black = binary.LittleEndian.Uint32(body[p+12 : p+16])
	x.rootVisual = binary.LittleEndian.Uint32(body[p+32 : p+36])
	x.rootDepth = body[p+38]
	x.seq = 1
	return x, nil
}

func (x *xconn) Close() { x.c.Close() }

func (x *xconn) newID() uint32 {
	id := x.idBase | (x.nextID & x.idMask)
	x.nextID++
	return id
}

func (x *xconn) req(opcode byte, data []byte) error {
	// data must already be a whole number of 4-byte units minus the 4-byte header.
	total := 4 + len(data)
	if total%4 != 0 {
		pad := pad4(total)
		data = append(data, make([]byte, pad)...)
		total += pad
	}
	hdr := []byte{opcode, 0, 0, 0}
	binary.LittleEndian.PutUint16(hdr[2:4], uint16(total/4))
	x.seq++
	if _, err := x.c.Write(append(hdr, data...)); err != nil {
		return err
	}
	return nil
}

// reqWithByte is for requests whose second header byte carries a field.
func (x *xconn) reqWithByte(opcode, b byte, data []byte) error {
	total := 4 + len(data)
	if total%4 != 0 {
		pad := pad4(total)
		data = append(data, make([]byte, pad)...)
		total += pad
	}
	hdr := []byte{opcode, b, 0, 0}
	binary.LittleEndian.PutUint16(hdr[2:4], uint16(total/4))
	x.seq++
	_, err := x.c.Write(append(hdr, data...))
	return err
}

func le32(vals ...uint32) []byte {
	b := make([]byte, 0, 4*len(vals))
	for _, v := range vals {
		b = binary.LittleEndian.AppendUint32(b, v)
	}
	return b
}

// CreateWindow with an event mask and white background.
func (x *xconn) createWindow(w, h int, title string) (uint32, error) {
	wid := x.newID()
	const (
		cwBackPixel   = 1 << 1
		cwEventMask   = 1 << 11
		eExposure     = 1 << 15
		eKeyPress     = 1 << 0
		eButtonPress  = 1 << 2
		eStructure    = 1 << 17
	)
	body := make([]byte, 0, 64)
	body = binary.LittleEndian.AppendUint32(body, wid)
	body = binary.LittleEndian.AppendUint32(body, x.rootWindow)
	body = binary.LittleEndian.AppendUint16(body, 0)              // x
	body = binary.LittleEndian.AppendUint16(body, 0)              // y
	body = binary.LittleEndian.AppendUint16(body, uint16(w))
	body = binary.LittleEndian.AppendUint16(body, uint16(h))
	body = binary.LittleEndian.AppendUint16(body, 0)              // border
	body = binary.LittleEndian.AppendUint16(body, 1)              // InputOutput
	body = binary.LittleEndian.AppendUint32(body, x.rootVisual)
	body = binary.LittleEndian.AppendUint32(body, cwBackPixel|cwEventMask)
	body = binary.LittleEndian.AppendUint32(body, x.white)
	body = binary.LittleEndian.AppendUint32(body, eExposure|eKeyPress|eButtonPress|eStructure)
	if err := x.reqWithByte(1, x.rootDepth, body); err != nil {
		return 0, err
	}
	x.setProp8(wid, 39 /* WM_NAME */, 31 /* STRING */, []byte(title))
	x.setProp8(wid, 36 /* WM_ICON_NAME */, 31, []byte(title))
	return wid, nil
}

// setProp8 is ChangeProperty with 8-bit format, Replace mode.
func (x *xconn) setProp8(win, prop, typ uint32, val []byte) error {
	body := le32(win, prop, typ)
	body = append(body, 8, 0, 0, 0)
	body = binary.LittleEndian.AppendUint32(body, uint32(len(val)))
	body = append(body, val...)
	return x.reqWithByte(18, 0 /* Replace */, body)
}

func (x *xconn) mapWindow(w uint32) error { return x.req(8, le32(w)) }

func (x *xconn) createGC(drawable uint32) (uint32, error) {
	gc := x.newID()
	body := le32(gc, drawable, gcForeground|gcBackground, x.black, x.white)
	return gc, x.req(55, body)
}

// GC attribute mask bits. Values in a LISTofVALUE must be ordered by bit number,
// so foreground always precedes background.
const (
	gcForeground = 1 << 2
	gcBackground = 1 << 3
	gcFontBit    = 1 << 14
)

// gcColors sets BOTH colours, and every text draw must go through it.
//
// ImageText8 paints the background as well as the glyphs, from the GC's
// background field. Setting only the foreground leaves the background at its
// default of pixel 0 — black — so black text drew as a solid black bar and the
// whole dialog came out as filled rectangles.
func (x *xconn) gcColors(gc, fg, bg uint32) error {
	return x.req(56, le32(gc, gcForeground|gcBackground, fg, bg))
}

func (x *xconn) gcForeground(gc, pixel uint32) error {
	return x.req(56, le32(gc, gcForeground, pixel))
}

func (x *xconn) openFont(name string) (uint32, error) {
	fid := x.newID()
	body := le32(fid)
	body = binary.LittleEndian.AppendUint16(body, uint16(len(name)))
	body = append(body, 0, 0)
	body = append(body, name...)
	return fid, x.req(45, body)
}

func (x *xconn) gcFont(gc, font uint32) error {
	return x.req(56, le32(gc, gcFontBit, font))
}

func (x *xconn) fillRect(d, gc uint32, px, py, w, h int) error {
	body := le32(d, gc)
	body = binary.LittleEndian.AppendUint16(body, uint16(px))
	body = binary.LittleEndian.AppendUint16(body, uint16(py))
	body = binary.LittleEndian.AppendUint16(body, uint16(w))
	body = binary.LittleEndian.AppendUint16(body, uint16(h))
	return x.req(70, body)
}

func (x *xconn) drawRect(d, gc uint32, px, py, w, h int) error {
	body := le32(d, gc)
	body = binary.LittleEndian.AppendUint16(body, uint16(px))
	body = binary.LittleEndian.AppendUint16(body, uint16(py))
	body = binary.LittleEndian.AppendUint16(body, uint16(w-1))
	body = binary.LittleEndian.AppendUint16(body, uint16(h-1))
	return x.req(67, body)
}

// text draws with ImageText8, which paints the background too — so redrawing a
// line does not need the area cleared first. Limited to 255 chars per request.
func (x *xconn) text(d, gc uint32, px, py int, s string) error {
	if len(s) > 255 {
		s = s[:255]
	}
	body := le32(d, gc)
	body = binary.LittleEndian.AppendUint16(body, uint16(px))
	body = binary.LittleEndian.AppendUint16(body, uint16(py))
	body = append(body, s...)
	return x.reqWithByte(76, byte(len(s)), body)
}

func (x *xconn) clearArea(w uint32) error {
	body := le32(w)
	body = append(body, 0, 0, 0, 0, 0, 0, 0, 0) // x,y,width,height = 0 -> whole window
	return x.reqWithByte(61, 0, body)
}

// nextEvent blocks for one event, discarding replies and errors. Returns the
// 32-byte event buffer.
func (x *xconn) nextEvent(timeout time.Duration) ([]byte, error) {
	buf := make([]byte, 32)
	if timeout > 0 {
		x.c.SetReadDeadline(time.Now().Add(timeout))
		defer x.c.SetReadDeadline(time.Time{})
	}
	for {
		if _, err := ioReadFull(x.c, buf); err != nil {
			return nil, err
		}
		code := buf[0] & 0x7f
		if code == 1 { // a reply: read and discard its extra length
			extra := int(binary.LittleEndian.Uint32(buf[4:8])) * 4
			if extra > 0 {
				junk := make([]byte, extra)
				if _, err := ioReadFull(x.c, junk); err != nil {
					return nil, err
				}
			}
			continue
		}
		return buf, nil
	}
}

func ioReadFull(c net.Conn, b []byte) (int, error) {
	got := 0
	for got < len(b) {
		n, err := c.Read(b[got:])
		if n > 0 {
			got += n
		}
		if err != nil {
			return got, err
		}
	}
	return got, nil
}

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}
