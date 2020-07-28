package bar

type Rectangle struct {
    Width, Height int
}

requires acc(r.Width) && acc(r.Height)
ensures acc(r.Width) && acc(r.Height)
ensures res == r.Width * r.Height
ensures old(r.Width) == r.Width && old(r.Height) == r.Height
func (r *Rectangle) Area() (res int) {
    return r.Width * r.Height
}
