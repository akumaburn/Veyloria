"""Exercise the production speech adapter with a clearly synthetic CPU tone boundary.

This tests routing/transport/buffering, not Ming's voice quality. No model downloads.
"""
from pathlib import Path
import torch
import app as production


class ToneBoundary:
    device = 'cpu'
    sample_rate = 24000
    local_path = Path('review-tone-fixture')

    def synthesize(self, **options):
        frames = torch.arange(7200) / self.sample_rate
        return .03 * torch.sin(2 * torch.pi * 220 * frames)


production.load_model = ToneBoundary
app = production.app
