package pkg

requires len(a) == 3
func test1(a [2]int) {
  assert false
}

requires cap(a) == 2
func test2(a [3]int) {
  assert false
}

requires a[1] == 42
func test3(a [2]int) {
  assert a[1] == 42
}

requires len(a) == n
func test4(n int, a [64]int) {
  assert n == cap(a)
}

requires cap(a) == n
func test5(n int, a [128]int) {
  assert n == len(a)
}

ensures len(a) == 4
func test6() (a [4]int) {
}

func test7() (b [4]int) {
  var a [4]int
  assert a == b
}

ensures a == b
func test8(a [4]int) (b [4]int) {
	b = a
}

ensures b[0][0] == 0 && b[0][1] == 0
ensures b[1] == a
func test9(a [2]int) (b [3][2]int) {
	b[1] = a
}

ensures res[0] != res[1]
func test10() (res [2]bool) {
  res[0] = true
  res[1] = false
}
