import unittest

from wechat_draft_publisher.wechat_api import WechatNewspicClient


class WechatPayloadTests(unittest.TestCase):
    def test_newspic_payload_uses_official_shape(self) -> None:
        payload = WechatNewspicClient.build_draft_payload(
            title="标题",
            content="正文",
            image_media_ids=["m1", "m2"],
            author="作者",
        )
        article = payload["articles"][0]
        self.assertEqual(article["article_type"], "newspic")
        self.assertEqual(article["image_info"]["image_list"], [
            {"image_media_id": "m1"},
            {"image_media_id": "m2"},
        ])
        self.assertEqual(article["author"], "作者")


if __name__ == "__main__":
    unittest.main()
