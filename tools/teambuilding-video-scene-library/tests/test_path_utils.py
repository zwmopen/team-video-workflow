from tb_scene.path_utils import sanitize_name


def test_sanitize_name_removes_windows_bad_chars():
    assert sanitize_name('A/B:C*D?E"F<G>H|') == "A_B_C_D_E_F_G_H"
